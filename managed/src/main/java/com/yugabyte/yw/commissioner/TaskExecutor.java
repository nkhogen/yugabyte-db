// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner;

import static com.yugabyte.yw.models.helpers.CommonUtils.getDurationSeconds;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.util.Throwables;
import com.google.common.collect.Sets;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.common.ha.PlatformReplicationManager;
import com.yugabyte.yw.common.password.RedactingService;
import com.yugabyte.yw.forms.ITaskParams;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.ScheduleTask;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.TaskInfo.State;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import com.yugabyte.yw.models.helpers.TaskType;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Summary;
import lombok.extern.slf4j.Slf4j;
import play.api.Play;

@Singleton
@Slf4j
public class TaskExecutor {

  // This is a map from the task types to the classes.
  private static final Map<TaskType, Class<? extends ITask>> taskTypeToTaskClassMap;

  // This holds the execution order of the tasks.
  // A task position starts from -1.
  private static final int taskInitialPosition = -1;

  // Task futures are waited for this long before checking abort status.
  private static final long taskSpinWaitTimeMs = 2000;

  private final ExecutorServiceProvider executorServiceProvider;

  // Current executing task UUID is available to the task and the subtask
  // via this thread-local variable.
  private final ThreadLocal<UUID> currentTaskUUID = new ThreadLocal<>();

  // A map from task UUID to its context while it is running.
  private final Map<UUID, TaskContext> tasksContexts = new ConcurrentHashMap<>();

  // A utility for Platform HA.
  private final PlatformReplicationManager replicationManager;

  // Metric names
  private static final String COMMISSIONER_TASK_WAITING_SEC_METRIC =
      "ybp_commissioner_task_waiting_sec";

  private static final String COMMISSIONER_TASK_EXECUTION_SEC_METRIC =
      "ybp_commissioner_task_execution_sec";

  // Metrics
  private static final Summary COMMISSIONER_TASK_WAITING_SEC =
      buildSummary(
          COMMISSIONER_TASK_WAITING_SEC_METRIC,
          "Duration between task creation and execution",
          KnownAlertLabels.TASK_TYPE.labelName());

  private static final Summary COMMISSIONER_TASK_EXECUTION_SEC =
      buildSummary(
          COMMISSIONER_TASK_EXECUTION_SEC_METRIC,
          "Duration of task execution",
          KnownAlertLabels.TASK_TYPE.labelName(),
          KnownAlertLabels.RESULT.labelName());

  static {
    // Initialize the map which holds the task types to their task class.
    Map<TaskType, Class<? extends ITask>> typeMap = new HashMap<>();

    for (TaskType taskType : TaskType.filteredValues()) {
      String className = "com.yugabyte.yw.commissioner.tasks." + taskType.toString();
      Class<? extends ITask> taskClass;
      try {
        taskClass = Class.forName(className).asSubclass(ITask.class);
        typeMap.put(taskType, taskClass);
        log.debug("Found task: {}", className);
      } catch (ClassNotFoundException e) {
        log.error("Could not find task for task type " + taskType, e);
      }
    }
    taskTypeToTaskClassMap = Collections.unmodifiableMap(typeMap);
    log.debug("Done loading tasks.");
  }

  static Summary buildSummary(String name, String description, String... labelNames) {
    return Summary.build(name, description)
        .quantile(0.5, 0.05)
        .quantile(0.9, 0.01)
        .maxAgeSeconds(TimeUnit.HOURS.toSeconds(1))
        .labelNames(labelNames)
        .register(CollectorRegistry.defaultRegistry);
  }

  // Metric writer method
  private void writeTaskWaitMetric(TaskType taskType, Instant creationTime) {
    COMMISSIONER_TASK_WAITING_SEC
        .labels(taskType.name())
        .observe(getDurationSeconds(creationTime, Instant.now()));
  }

  // Metric writer method
  private void writeTaskStateMetric(TaskType taskType, Instant startTime, State state) {
    COMMISSIONER_TASK_EXECUTION_SEC
        .labels(taskType.name(), State.Success.name())
        .observe(getDurationSeconds(startTime, Instant.now()));
  }

  @FunctionalInterface
  public interface TaskCompletetionListener {
    void onCompleted(ITask task, Exception e);
  }

  /** TaskContext holds the information of a running submitted task */
  private class TaskContext {
    private final UUID taskUUID;

    private final Set<SubTaskRunner> subTaskRunners =
        Collections.newSetFromMap(new ConcurrentHashMap<SubTaskRunner, Boolean>());

    private final ExecutorService executorService;

    private final TaskRunner taskRunner;

    private final AtomicInteger numSubTasksCompleted;

    private volatile SubTaskGroupType subTaskGroupType;

    private volatile boolean abort;

    // Execution position of subtasks.
    private int position = 0;

    TaskContext(TaskRunner taskRunner, ExecutorService executorService) {
      this.taskUUID = taskRunner.getTaskUUID();
      this.executorService = executorService;
      // position for subtask starts from parent position + 1
      this.position = taskRunner.getTaskPosition() + 1;
      this.taskRunner = taskRunner;
      this.subTaskGroupType = UserTaskDetails.SubTaskGroupType.Invalid;
      this.numSubTasksCompleted = new AtomicInteger(0);
    }
  }

  @Inject
  public TaskExecutor(
      ExecutorServiceProvider executorServiceProvider,
      PlatformReplicationManager replicationManager) {
    this.executorServiceProvider = executorServiceProvider;
    this.replicationManager = replicationManager;
  }

  public TaskRunner createTaskRunner(TaskType taskType, ITaskParams taskParams) {
    ITask task = Play.current().injector().instanceOf(taskTypeToTaskClassMap.get(taskType));
    task.initialize(taskParams);
    TaskInfo taskInfo = createTaskInfo(task, taskInitialPosition);
    taskInfo.save();
    TaskRunner taskRunner = new TaskRunner(task, taskInfo);
    return taskRunner;
  }

  public UUID submit(TaskRunner taskRunner) {
    TaskType taskType = taskRunner.getTaskType();
    ExecutorService executorService = executorServiceProvider.getExecutorService(taskType);
    TaskContext taskContext = new TaskContext(taskRunner, executorService);

    UUID taskUUID = taskRunner.getTaskUUID();
    tasksContexts.put(taskUUID, taskContext);
    try {
      taskRunner.updateScheduledTime();
      taskRunner.future = executorService.submit(taskRunner);
    } catch (Exception e) {
      // Update task state on submission failure.
      tasksContexts.remove(taskUUID);
      taskRunner.setTaskState(TaskInfo.State.Failure);
      taskRunner.updateTaskDetailsOnError(e);
    }
    return taskUUID;
  }

  // This is exposed for supporting SubTaskGroup.
  public TaskInfo getCurrentTaskInfo() {
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (!optional.isPresent()) {
      throw new IllegalStateException("getCurrentTaskInfo must be called from the task run method");
    }
    TaskContext taskContext = optional.get();
    return taskContext.taskRunner.taskInfo;
  }

  public boolean isTaskActive(UUID taskUUID) {
    return tasksContexts.containsKey(taskUUID);
  }

  // Signal to stop the task if it is already running.
  // It does not stop the task immediately.
  public Optional<TaskInfo> stop(UUID taskUUID, boolean forcefully) {
    log.info("Stopping task {}", taskUUID);
    TaskContext taskContext = tasksContexts.get(taskUUID);
    if (taskContext == null) {
      return Optional.empty();
    }
    // Signal abort to the task context.
    taskContext.abort = true;
    // Update the task state in the memory and DB.
    taskContext.taskRunner.compareAndSetTaskState(
        Sets.immutableEnumSet(State.Initializing, State.Created, State.Running), State.Abort);
    if (forcefully) {
      for (SubTaskRunner taskRunner : taskContext.subTaskRunners) {
        if (taskRunner.future != null) {
          taskRunner.future.cancel(true);
        }
      }
      if (taskContext.taskRunner.future != null) {
        taskContext.taskRunner.future.cancel(true);
      }
    }
    return Optional.of(taskContext.taskRunner.taskInfo);
  }

  public void setSubTaskGroupType(SubTaskGroupType subTaskGroupType) {
    if (subTaskGroupType == null) {
      return;
    }
    log.info("Setting subtask group type to {}", subTaskGroupType);
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (!optional.isPresent()) {
      throw new IllegalStateException(
          "setSubTaskGroupType must be called from the task run method");
    }
    TaskContext taskContext = optional.get();
    taskContext.subTaskGroupType = subTaskGroupType;
    for (SubTaskRunner subTaskRunner : taskContext.subTaskRunners) {
      subTaskRunner.taskInfo.setSubTaskGroupType(subTaskGroupType);
      subTaskRunner.taskInfo.save();
    }
  }

  // This is exposed for supporting SubTaskGroup.
  public void setSubTaskState(TaskInfo.State userTaskState) {
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (!optional.isPresent()) {
      throw new IllegalStateException("setSubTaskState must be called from the task run method");
    }
    TaskContext taskContext = optional.get();
    for (SubTaskRunner taskRunner : taskContext.subTaskRunners) {
      taskRunner.setTaskState(userTaskState);
    }
  }

  // Submit a subtask task for asynchronous execution.
  public void async(ITask task) {
    log.info("Async called for {}", task);
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (!optional.isPresent()) {
      throw new IllegalStateException("async must be called from the task run method");
    }
    TaskContext taskContext = optional.get();
    TaskInfo taskInfo = createTaskInfo(task, taskContext.position);
    taskInfo.setParentUuid(taskContext.taskUUID);
    taskInfo.setSubTaskGroupType(taskContext.subTaskGroupType);
    taskInfo.save();
    SubTaskRunner taskRunner = new SubTaskRunner(task, taskInfo);
    taskContext.subTaskRunners.add(taskRunner);
  }

  public void sync(ITask task) {
    sync(task, Duration.ZERO);
  }

  public void sync(ITask task, Duration timeout) {
    log.info("Sync called for {}", task);
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (!optional.isPresent()) {
      throw new IllegalStateException("sync must be called from the task run method");
    }
    TaskContext taskContext = optional.get();
    if (taskContext.subTaskRunners.size() > 0) {
      // This prevents calling of this method after async.
      throw new IllegalStateException("sync cannot be called when there are pending tasks");
    }
    TaskInfo taskInfo = createTaskInfo(task, taskContext.position);
    taskInfo.setParentUuid(taskContext.taskUUID);
    taskInfo.setSubTaskGroupType(taskContext.subTaskGroupType);
    taskInfo.save();
    SubTaskRunner subTaskRunner = new SubTaskRunner(task, taskInfo);
    taskContext.subTaskRunners.add(subTaskRunner);
    try {
      subTaskRunner.updateScheduledTime();
      subTaskRunner.future = taskContext.executorService.submit(subTaskRunner);
    } catch (RuntimeException e) {
      taskContext.subTaskRunners.clear();
      subTaskRunner.setTaskState(TaskInfo.State.Failure);
      subTaskRunner.updateTaskDetailsOnError(e);
      throw e;
    }
    if (subTaskRunner.future != null) {
      waitForInternal(timeout, null);
    }
  }

  // Wait for submitted asynchronous tasks to complete indefinitely.
  public void waitAsyncTasks() {
    waitAsyncTasks(Duration.ZERO, null);
  }

  public void waitAsyncTasks(TaskCompletetionListener listener) {
    waitAsyncTasks(Duration.ZERO, listener);
  }

  // Wait for submitted asynchronous tasks to complete within a timeout.
  public void waitAsyncTasks(Duration timeout, TaskCompletetionListener listener) {
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (!optional.isPresent()) {
      throw new IllegalStateException("waitFor must be called from the task run method");
    }
    TaskContext taskContext = optional.get();
    ExecutorService executorService = taskContext.executorService;
    RuntimeException anyRe = null;
    try {
      for (SubTaskRunner subTaskRunner : taskContext.subTaskRunners) {
        RuntimeException re = null;
        try {
          subTaskRunner.updateScheduledTime();
          subTaskRunner.future = executorService.submit(subTaskRunner);
        } catch (RuntimeException e) {
          // Subtask submission failed.
          re = e;
          anyRe = e;
          taskContext.subTaskRunners.clear();
          subTaskRunner.setTaskState(TaskInfo.State.Failure);
          subTaskRunner.updateTaskDetailsOnError(e);
          break;
        } finally {
          if (listener != null) {
            try {
              listener.onCompleted(subTaskRunner.task, re);
            } catch (RuntimeException e) {
              // Ignore
            }
          }
        }
      }
      // Wait for the existing tasks to finish
      // even if some submissions failed.
      waitForInternal(timeout, listener);
      if (anyRe != null) {
        throw anyRe;
      }
    } finally {
      taskContext.subTaskRunners.clear();
    }
  }

  // This returns the position of the current subtask.
  // It is added to support SubTaskGroupQueue.
  public int getCurrentSubTaskPosition() {
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (!optional.isPresent()) {
      throw new IllegalStateException(
          "getCurrentSubTaskPosition must be called from the task run method");
    }
    TaskContext taskContext = optional.get();
    return taskContext.position;
  }

  private Optional<TaskContext> getCurrentTaskContext() {
    TaskContext taskContext = null;
    UUID taskUUID = currentTaskUUID.get();
    if (taskUUID == null || (taskContext = tasksContexts.get(taskUUID)) == null) {
      return Optional.empty();
    }
    return Optional.of(taskContext);
  }

  /**
   * Wait for all the subtasks to complete. In this method, the state updates on exceptions are done
   * for tasks which are not yet running.
   */
  private void waitForInternal(Duration timeout, TaskCompletetionListener listener) {
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (!optional.isPresent()) {
      throw new IllegalStateException("waitForInternal must be called from the task run method");
    }

    TaskContext taskContext = optional.get();
    Instant waitStartTime = Instant.now();
    List<TaskRunnerBase> subTaskRunners =
        taskContext
            .subTaskRunners
            .stream()
            .filter(t -> t.future != null)
            .collect(Collectors.toList());
    if (subTaskRunners.size() > 0) {
      // Prepare for the next subtask position.
      taskContext.position++;
    }
    RuntimeException anyEx = null;
    while (subTaskRunners.size() > 0) {
      RuntimeException re = null;
      Iterator<TaskRunnerBase> iter = subTaskRunners.iterator();
      while (iter.hasNext()) {
        TaskRunnerBase subTaskRunner = iter.next();
        Future<?> future = subTaskRunner.future;
        // Check if the abort signal is set.
        if (taskContext.abort) {
          subTaskRunner.setTaskState(TaskInfo.State.Aborting);
        }

        try {
          future.get(taskSpinWaitTimeMs, TimeUnit.MILLISECONDS);
          // Remove the subtask because it is completed.
          iter.remove();
        } catch (ExecutionException e) {
          re = Throwables.propagate(e.getCause());
        } catch (TimeoutException e) {
          Duration elapsed = Duration.between(waitStartTime, Instant.now());
          log.debug("Task {} has taken {}ms", subTaskRunner.getTaskUUID(), elapsed.toMillis());
          if (!timeout.isZero() && elapsed.compareTo(timeout) > 0) {
            re = Throwables.propagate(e);
            future.cancel(true);
            // Update the subtask state to aborted if the execution timed out.
            subTaskRunner.setTaskState(TaskInfo.State.Aborted);
            subTaskRunner.updateTaskDetailsOnError(re);
          }
          // Otherwise, ignore error and check again.
        } catch (CancellationException e) {
          // If the subtask is cancelled before it is picked up for execution,
          // the state needs to be updated here.
          if (subTaskRunner.compareAndSetTaskState(
              TaskInfo.State.Created, TaskInfo.State.Aborted)) {
            subTaskRunner.updateTaskDetailsOnError(re);
          }
          re = e;
        } catch (Exception e) {
          // All other exceptions are interpreted as Failure only if
          // the subtask is not in running state.
          if (subTaskRunner.compareAndSetTaskState(
              TaskInfo.State.Created, TaskInfo.State.Failure)) {
            subTaskRunner.updateTaskDetailsOnError(re);
          }
          re = Throwables.propagate(e);
        } finally {
          if (re != null) {
            // Do not try to check the status again.
            iter.remove();
            anyEx = re;
          }
          if (listener != null) {
            try {
              listener.onCompleted(subTaskRunner.task, re);
            } catch (RuntimeException e) {
              // Ignore
            }
          }
        }
      }
    }
    if (anyEx != null) {
      throw anyEx;
    }
  }

  public String getTaskOwner() {
    String hostname = "";
    try {
      hostname = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      log.error("Could not determine the hostname", e);
    }
    return hostname;
  }

  private TaskInfo createTaskInfo(ITask task, int position) {
    TaskType taskType = TaskType.valueOf(task.getClass().getSimpleName());
    // Create a new task info object.
    TaskInfo taskInfo = new TaskInfo(taskType);
    // Set the task details.
    taskInfo.setTaskDetails(RedactingService.filterSecretFields(task.getTaskDetails()));
    // Set the owner info.
    String hostname = "";
    try {
      hostname = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      log.error("Could not determine the hostname", e);
    }
    taskInfo.setOwner(getTaskOwner());
    taskInfo.setPosition(position);
    return taskInfo;
  }

  /**
   * Base implementation of a task runner which handles the state book-keeping after the task has
   * started running.
   */
  public class TaskRunnerBase implements Runnable {
    final ITask task;
    final TaskInfo taskInfo;

    Instant taskScheduledTime;
    Instant taskStartTime;
    Instant taskCompletionTime;

    // Future of the task that is set after it is submitted to the ExecutorService.
    // It is set and read from the same thread.
    Future<?> future = null;

    protected TaskRunnerBase(ITask task, TaskInfo taskInfo) {
      this.task = task;
      this.taskInfo = taskInfo;
      this.taskScheduledTime = Instant.now();
    }

    @Override
    public void run() {
      RuntimeException re = null;
      try {
        writeTaskWaitMetric(taskInfo.getTaskType(), taskInfo.getCreationTime().toInstant());
        taskStartTime = Instant.now();
        // Check for abort before starting the task.
        TaskContext taskContext = getCurrentTaskContext().get();
        if (taskContext.abort) {
          throw new CancellationException("Subtask " + task.getName() + " is aborted");
        }
        log.debug("Invoking run() of task {}", task.getName());
        setTaskState(TaskInfo.State.Running);
        task.run();
        setTaskState(TaskInfo.State.Success);
      } catch (CancellationException e) {
        // Task handling the interrupt flag must throw CancellationException.
        setTaskState(TaskInfo.State.Aborted);
        writeTaskStateMetric(taskInfo.getTaskType(), taskStartTime, TaskInfo.State.Aborted);
        re = e;
      } catch (Exception e) {
        setTaskState(TaskInfo.State.Failure);
        writeTaskStateMetric(taskInfo.getTaskType(), taskStartTime, TaskInfo.State.Failure);
        // Propagate error to the task invoker.
        re = Throwables.propagate(e);
      } finally {
        taskCompletionTime = Instant.now();
        if (re != null) {
          updateTaskDetailsOnError(re);
          throw re;
        }
      }
    }

    public void updateScheduledTime() {
      taskScheduledTime = Instant.now();
    }

    public void setTaskUUID(UUID taskUUID) {
      taskInfo.setTaskUUID(taskUUID);
    }

    public UUID getTaskUUID() {
      return taskInfo.getTaskUUID();
    }

    public TaskType getTaskType() {
      return taskInfo.getTaskType();
    }

    public synchronized TaskInfo.State getTaskState() {
      return taskInfo.getTaskState();
    }

    public synchronized void setTaskState(TaskInfo.State state) {
      taskInfo.setTaskState(state);
      taskInfo.save();
    }

    public int getTaskPosition() {
      return taskInfo.getPosition();
    }

    public boolean isTaskRunning() {
      return taskInfo.getTaskState() == TaskInfo.State.Running;
    }

    public boolean hasTaskSucceeded() {
      return taskInfo.getTaskState() == TaskInfo.State.Success;
    }

    public boolean hasTaskFailed() {
      return taskInfo.getTaskState() == TaskInfo.State.Failure;
    }

    public synchronized boolean compareAndSetTaskState(
        TaskInfo.State expected, TaskInfo.State state) {
      return compareAndSetTaskState(Sets.immutableEnumSet(expected), state);
    }

    public synchronized boolean compareAndSetTaskState(
        Set<TaskInfo.State> expectedStates, TaskInfo.State state) {
      TaskInfo.State currentState = taskInfo.getTaskState();
      if (expectedStates.contains(currentState)) {
        taskInfo.setTaskState(state);
        taskInfo.save();
        return true;
      }
      return false;
    }

    public void updateTaskDetailsOnError(Exception e) {
      if (e == null) {
        return;
      }
      String errorString =
          "Failed to execute task "
              + StringUtils.abbreviate(taskInfo.getTaskDetails().toString(), 500)
              + ", hit error:\n\n"
              + StringUtils.abbreviateMiddle(e.getMessage(), "...", 3000)
              + ".";
      log.error(
          "Failed to execute task type {} UUID {} details {}, hit error.",
          taskInfo.getTaskType().toString(),
          taskInfo.getTaskUUID().toString(),
          taskInfo.getTaskDetails(),
          e);

      // TODO: Avoid this deepCopy
      ObjectNode details = taskInfo.getTaskDetails().deepCopy();
      details.put("errorString", errorString);
      taskInfo.setTaskDetails(details);
      taskInfo.save();
    }
  }

  /** Task runner */
  public class TaskRunner extends TaskRunnerBase {

    TaskRunner(ITask task, TaskInfo taskInfo) {
      super(task, taskInfo);
    }

    @Override
    public void run() {
      currentTaskUUID.set(taskInfo.getTaskUUID());
      try {
        super.run();
      } catch (Exception e) {
        // Do not propagate the error from the parent task
        // because there is no one handling it.
        log.error("Error running task", e);
      } finally {
        // Remove the task context
        tasksContexts.remove(taskInfo.getTaskUUID());
        // Cleanup is required for threads in a pool
        currentTaskUUID.remove();
        // Update the customer task to a completed state.
        CustomerTask customerTask = CustomerTask.findByTaskUUID(taskInfo.getTaskUUID());
        if (customerTask != null) {
          customerTask.markAsCompleted();
        }

        // In case it was a scheduled task, update state of the task.
        ScheduleTask scheduleTask = ScheduleTask.fetchByTaskUUID(taskInfo.getTaskUUID());
        if (scheduleTask != null) {
          scheduleTask.setCompletedTime();
        }
        // Run a one-off Platform HA sync every time a task finishes.
        replicationManager.oneOffSync();
      }
    }

    public void doHeartbeat() {
      taskInfo.markAsDirty();
      taskInfo.save();
    }
  }

  /** Subtask runner */
  public class SubTaskRunner extends TaskRunnerBase {

    SubTaskRunner(ITask task, TaskInfo taskInfo) {
      super(task, taskInfo);
    }

    @Override
    public void run() {
      // Make the context key available in the subtask thread
      TaskExecutor.this.currentTaskUUID.set(taskInfo.getParentUUID());
      try {
        super.run();
      } finally {
        // Cleanup is required for threads in a pool
        currentTaskUUID.remove();
      }
    }
  }
}
