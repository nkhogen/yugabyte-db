// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner;

import static com.yugabyte.yw.models.helpers.CommonUtils.getDurationSeconds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.util.Throwables;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.MoreExecutors;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Summary;
import lombok.extern.slf4j.Slf4j;
import play.api.Play;
import play.inject.ApplicationLifecycle;

@Singleton
@Slf4j
public class TaskExecutor {

  // This is a map from the task types to the classes.
  private static final Map<TaskType, Class<? extends ITask>> TASK_TYPE_TO_CLASS_MAP;

  // This holds the execution order of the tasks.
  // A task position starts from -1.
  private static final int TASK_INITIAL_POSITION = -1;

  // Task futures are waited for this long before checking abort status.
  private static final long TASK_SPIN_WAIT_INTERVAL_MS = 2000;

  private final Duration defaultAbortTaskTimeout = Duration.ofSeconds(60);

  private final ExecutorServiceProvider executorServiceProvider;

  // Current executing task UUID is available to the task and the subtask
  // via this thread-local variable.
  private final ThreadLocal<UUID> currentTaskUUID = new ThreadLocal<>();

  // A map from task UUID to its context while it is running.
  private final Map<UUID, TaskContext> tasksContexts = new ConcurrentHashMap<>();

  // A utility for Platform HA.
  private final PlatformReplicationManager replicationManager;

  private final AtomicBoolean isShutdown = new AtomicBoolean();

  private static final String COMMISSIONER_TASK_WAITING_SEC_METRIC =
      "ybp_commissioner_task_waiting_sec";

  private static final String COMMISSIONER_TASK_EXECUTION_SEC_METRIC =
      "ybp_commissioner_task_execution_sec";

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
    TASK_TYPE_TO_CLASS_MAP = Collections.unmodifiableMap(typeMap);
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

  // This writes the waiting time metric.
  private void writeTaskWaitMetric(TaskType taskType, Instant creationTime) {
    COMMISSIONER_TASK_WAITING_SEC
        .labels(taskType.name())
        .observe(getDurationSeconds(creationTime, Instant.now()));
  }

  // This writes the execution time metric.
  private void writeTaskStateMetric(TaskType taskType, Instant startTime, State state) {
    COMMISSIONER_TASK_EXECUTION_SEC
        .labels(taskType.name(), State.Success.name())
        .observe(getDurationSeconds(startTime, Instant.now()));
  }

  /** Listener to get called when a task is completed */
  @FunctionalInterface
  public interface TaskCompletetionListener {
    void onCompletion(ITask task, Throwable t);
  }

  /** TaskContext holds the information of a running submitted task */
  private class TaskContext {
    private final UUID taskUUID;

    private final Set<SubTaskRunner> subTaskRunners =
        Collections.newSetFromMap(new ConcurrentHashMap<SubTaskRunner, Boolean>());

    private final ExecutorService executorService;

    private final TaskRunner taskRunner;

    private final AtomicInteger numSubTasksCompleted;

    private SubTaskGroupType subTaskGroupType;

    private Instant abortTime;

    // Execution position of subtasks.
    private int position = 0;

    TaskContext(TaskRunner taskRunner, ExecutorService executorService) {
      this.taskUUID = taskRunner.getTaskUUID();
      this.executorService = executorService;
      this.position = taskRunner.getTaskPosition() + 1;
      this.taskRunner = taskRunner;
      this.subTaskGroupType = UserTaskDetails.SubTaskGroupType.Invalid;
      this.numSubTasksCompleted = new AtomicInteger(0);
    }
  }

  @Inject
  public TaskExecutor(
      ApplicationLifecycle lifecycle,
      ExecutorServiceProvider executorServiceProvider,
      PlatformReplicationManager replicationManager) {
    this.executorServiceProvider = executorServiceProvider;
    this.replicationManager = replicationManager;
    lifecycle.addStopHook(
        () ->
            CompletableFuture.supplyAsync(() -> TaskExecutor.this.shutdown(Duration.ofMinutes(3))));
  }

  // Returns the class of the task for the given task type.
  public static Class<? extends ITask> getTaskClass(TaskType taskType) {
    Class<? extends ITask> taskClass = TASK_TYPE_TO_CLASS_MAP.get(taskType);
    if (taskClass == null) {
      throw new IllegalArgumentException("Invalid task type: " + taskType);
    }
    return taskClass;
  }

  // Shuts down the task executor.
  public boolean shutdown(Duration timeout) {
    if (!isShutdown.compareAndSet(false, true)) {
      return false;
    }
    Instant abortTime = Instant.now();
    for (UUID taskUUID : tasksContexts.keySet()) {
      TaskContext taskContext = tasksContexts.get(taskUUID);
      if (taskContext == null) {
        continue;
      }
      taskContext.abortTime = abortTime;
    }
    while (!tasksContexts.isEmpty()) {
      try {
        Thread.sleep(TASK_SPIN_WAIT_INTERVAL_MS);
      } catch (InterruptedException e) {
        return false;
      }
      if (timeout != null && !timeout.isZero()) {
        Duration elapsed = Duration.between(abortTime, Instant.now());
        if (elapsed.compareTo(timeout) > 0) {
          return false;
        }
      }
    }
    return true;
  }

  private void checkTaskExecutorState() {
    if (isShutdown.get()) {
      throw new IllegalStateException("TaskExecutor is shutting down");
    }
  }

  // Create a task runner for a task type and params.
  public TaskRunner createTaskRunner(TaskType taskType, ITaskParams taskParams) {
    ITask task = Play.current().injector().instanceOf(TASK_TYPE_TO_CLASS_MAP.get(taskType));
    task.initialize(taskParams);
    return createTaskRunner(task);
  }

  // Create a task runner for a task created externally.
  public TaskRunner createTaskRunner(ITask task) {
    TaskInfo taskInfo = createTaskInfo(task);
    taskInfo.setPosition(TASK_INITIAL_POSITION);
    TaskRunner taskRunner = new TaskRunner(task, taskInfo);
    return taskRunner;
  }

  // Submit a task to the task executor.
  public UUID submit(TaskRunner taskRunner) {
    checkTaskExecutorState();
    // Task must be saved right before submitting to prevent
    // tasks from dangling due to shutdown of the task executor.
    taskRunner.saveTask();
    TaskType taskType = taskRunner.getTaskType();
    ExecutorService executorService = executorServiceProvider.getExecutorService(taskType);
    return submit(taskRunner, executorService);
  }

  // Submit a task to the task executor with a specific executor service.
  public UUID submit(TaskRunner taskRunner, ExecutorService executorService) {
    checkTaskExecutorState();
    // Task must be saved right before submitting to prevent
    // tasks from dangling due to shutdown of the task executor.
    taskRunner.saveTask();
    TaskType taskType = taskRunner.getTaskType();
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

  // This waits for the parent task to complete.
  public void waitForTask(UUID taskUUID) {
    waitForTask(taskUUID, null);
  }

  // This waits for the parent task to complete.
  public void waitForTask(UUID taskUUID, Duration duration) {
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (optional.isPresent()) {
      throw new IllegalStateException("waitForTask must not be called from task");
    }
    TaskContext taskContext = tasksContexts.get(taskUUID);
    if (taskContext == null || taskContext.taskRunner.future == null) {
      return;
    }
    try {
      if (duration == null || duration.isZero()) {
        taskContext.taskRunner.future.get();
      } else {
        taskContext.taskRunner.future.get(duration.toMillis(), TimeUnit.MILLISECONDS);
      }
    } catch (ExecutionException e) {
      Throwables.propagate(e.getCause());
    } catch (Exception e) {
      Throwables.propagate(e);
    }
  }

  // Update a running task.
  // It is useful for saving updated task parameters
  // after performing pre-processing like master selection
  // node index and name assignment.
  public void updateTask(ITask task) {
    log.info("UpdateTask called for {}", task);
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (!optional.isPresent()) {
      throw new IllegalStateException("updateTask must be called from the task run method");
    }
    TaskContext taskContext = optional.get();
    taskContext.taskRunner.setTaskDetails(
        RedactingService.filterSecretFields(task.getTaskDetails()));
  }

  // This is exposed for supporting SubTaskGroup.
  // It returns the parent task info.
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
    ITask task = taskContext.taskRunner.task;
    if (!task.isAbortable()) {
      throw new RuntimeException("Task " + task.getName() + " is not abortable");
    }
    // Signal abort to the task context.
    taskContext.abortTime = Instant.now();
    // Update the task state in the memory and DB.
    taskContext.taskRunner.compareAndSetTaskState(
        Sets.immutableEnumSet(State.Initializing, State.Created, State.Running), State.Aborting);
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
    // TODO Updating the task infos is required to support
    // SubTaskGroup.setSubTaskGroupType.
    for (SubTaskRunner subTaskRunner : taskContext.subTaskRunners) {
      subTaskRunner.taskInfo.setSubTaskGroupType(subTaskGroupType);
      subTaskRunner.taskInfo.save();
    }
  }

  // Submit a subtask task for asynchronous execution
  // from the parent task.
  public void async(ITask task) {
    log.info("Async called for {}", task);
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (!optional.isPresent()) {
      throw new IllegalStateException("async must be called from the task run method");
    }
    TaskContext taskContext = optional.get();
    TaskInfo taskInfo = createTaskInfo(task);
    taskInfo.setPosition(taskContext.position);
    taskInfo.setParentUuid(taskContext.taskUUID);
    taskInfo.setSubTaskGroupType(taskContext.subTaskGroupType);
    taskInfo.save();
    SubTaskRunner taskRunner = new SubTaskRunner(task, taskInfo);
    taskContext.subTaskRunners.add(taskRunner);
  }

  // Submit a subtask task for asynchronous execution
  // from the parent task.
  public void sync(ITask task) {
    sync(task, Duration.ZERO);
  }

  // Submit a subtask task for synchronous execution
  // from the parent task.
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
    TaskInfo taskInfo = createTaskInfo(task);
    taskInfo.setPosition(taskContext.position);
    taskInfo.setParentUuid(taskContext.taskUUID);
    taskInfo.setSubTaskGroupType(taskContext.subTaskGroupType);
    taskInfo.save();
    SubTaskRunner subTaskRunner = new SubTaskRunner(task, taskInfo);
    try {
      taskContext.subTaskRunners.add(subTaskRunner);
      try {
        subTaskRunner.updateScheduledTime();
        subTaskRunner.future = taskContext.executorService.submit(subTaskRunner);
      } catch (RuntimeException e) {
        // Submission must have failed.
        subTaskRunner.setTaskState(TaskInfo.State.Failure);
        subTaskRunner.updateTaskDetailsOnError(e);
        throw e;
      }
      waitForInternal(timeout, null);
    } finally {
      taskContext.subTaskRunners.clear();
    }
  }

  // Wait for submitted asynchronous tasks to complete indefinitely.
  public void waitAsyncTasks() {
    waitAsyncTasks(Duration.ZERO, null);
  }

  // Wait for submitted asynchronous tasks to complete indefinitely
  // with a completion listener callback.
  public void waitAsyncTasks(TaskCompletetionListener listener) {
    waitAsyncTasks(Duration.ZERO, listener);
  }

  // Wait for submitted asynchronous tasks to complete within a timeout
  // with a completion listener callback.
  public void waitAsyncTasks(Duration timeout, TaskCompletetionListener listener) {
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (!optional.isPresent()) {
      throw new IllegalStateException("waitAsyncTasks must be called from the task run method");
    }
    TaskContext taskContext = optional.get();
    ExecutorService executorService = taskContext.executorService;
    RuntimeException anyRe = null;
    try {
      for (SubTaskRunner subTaskRunner : taskContext.subTaskRunners) {
        try {
          subTaskRunner.updateScheduledTime();
          subTaskRunner.future = executorService.submit(subTaskRunner);
        } catch (RuntimeException e) {
          // Subtask submission failed.
          anyRe = e;
          subTaskRunner.setTaskState(TaskInfo.State.Failure);
          subTaskRunner.updateTaskDetailsOnError(e);
          if (listener != null) {
            try {
              listener.onCompletion(subTaskRunner.task, e);
            } catch (RuntimeException e1) {
              // Ignore
            }
          }
          break;
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
      log.warn("Task context is not found");
      // Test creates subtasks directly.
      return 0;
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

    Throwable anyEx = null;
    while (subTaskRunners.size() > 0) {
      Throwable t = null;
      Iterator<TaskRunnerBase> iter = subTaskRunners.iterator();
      while (iter.hasNext()) {
        TaskRunnerBase subTaskRunner = iter.next();
        Future<?> future = subTaskRunner.future;

        try {
          future.get(TASK_SPIN_WAIT_INTERVAL_MS, TimeUnit.MILLISECONDS);
          iter.remove();
          publishTaskCompleted(listener, subTaskRunner.task, null);
        } catch (ExecutionException e) {
          t = e.getCause();
        } catch (TimeoutException e) {
          Duration elapsed = Duration.between(waitStartTime, Instant.now());
          if (log.isTraceEnabled()) {
            log.trace("Task {} has taken {}ms", subTaskRunner.getTaskUUID(), elapsed.toMillis());
          }
          // If the subtask execution took long,
          // it is interrupted.
          if ((!timeout.isZero() && elapsed.compareTo(timeout) > 0)
              || (taskContext.abortTime != null
                  && Duration.between(taskContext.abortTime, Instant.now())
                          .compareTo(defaultAbortTaskTimeout)
                      > 0
                  && subTaskRunner.task.isAbortable())) {
            t = e;
            future.cancel(true);
            // Update the subtask state to aborted if the execution timed out.
            subTaskRunner.setTaskState(TaskInfo.State.Aborted);
            subTaskRunner.updateTaskDetailsOnError(e);
          }
          // Otherwise, ignore error and check again.
        } catch (CancellationException e) {
          t = e;
          // If the subtask is cancelled before it is picked up for execution,
          // the state needs to be updated here.
          if (subTaskRunner.compareAndSetTaskState(
              TaskInfo.State.Created, TaskInfo.State.Aborted)) {
            subTaskRunner.updateTaskDetailsOnError(e);
          }
        } catch (Exception e) {
          t = e;
          // All other exceptions are interpreted as Failure only if
          // the subtask is not in running state.
          if (subTaskRunner.compareAndSetTaskState(
              TaskInfo.State.Created, TaskInfo.State.Failure)) {
            subTaskRunner.updateTaskDetailsOnError(e);
          }
        } finally {
          if (t != null) {
            log.error("Error occurred in subtask " + subTaskRunner.taskInfo, t);
            // Do not try to check the status again.
            iter.remove();
            anyEx = t;
            publishTaskCompleted(listener, subTaskRunner.task, t);
          }
        }
      }
    }
    if (anyEx != null) {
      Throwables.propagate(anyEx);
    }
    // Prepare for the next subtask position.
    taskContext.position++;
  }

  private void publishTaskCompleted(TaskCompletetionListener listener, ITask task, Throwable t) {
    if (listener != null) {
      try {
        listener.onCompletion(task, t);
      } catch (RuntimeException e) {
        // Ignore error.
      }
    }
  }

  public static String getTaskOwner() {
    String hostname = "";
    try {
      hostname = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      log.error("Could not determine the hostname", e);
    }
    return hostname;
  }

  @VisibleForTesting
  TaskInfo createTaskInfo(ITask task) {
    TaskType taskType = TaskType.valueOf(task.getClass().getSimpleName());
    // Create a new task info object.
    TaskInfo taskInfo = new TaskInfo(taskType);
    // Set the task details.
    taskInfo.setTaskDetails(RedactingService.filterSecretFields(task.getTaskDetails()));
    // Set the owner info.
    taskInfo.setOwner(getTaskOwner());
    return taskInfo;
  }

  /**
   * Base implementation of a task runner which handles the state update after the task has started
   * running.
   */
  public class TaskRunnerBase implements Runnable {
    final ITask task;
    final TaskInfo taskInfo;

    Instant taskScheduledTime;
    Instant taskStartTime;
    Instant taskCompletionTime;

    // Future of the task that is set after it is submitted to the ExecutorService.
    Future<?> future = null;

    protected TaskRunnerBase(ITask task, TaskInfo taskInfo) {
      this.task = task;
      this.taskInfo = taskInfo;
      this.taskScheduledTime = Instant.now();
    }

    @Override
    public void run() {
      Throwable t = null;
      TaskType taskType = taskInfo.getTaskType();
      taskStartTime = Instant.now();
      try {
        writeTaskWaitMetric(taskType, taskInfo.getCreationTime().toInstant());
        // Check for abort before starting the task.
        TaskContext taskContext = getCurrentTaskContext().get();
        if (taskContext.abortTime != null) {
          throw new CancellationException("Subtask " + task.getName() + " is aborted");
        }
        log.debug("Invoking run() of task {}", task.getName());
        setTaskState(TaskInfo.State.Running);
        task.run();
        setTaskState(TaskInfo.State.Success);
        writeTaskStateMetric(taskType, taskStartTime, TaskInfo.State.Success);
      } catch (CancellationException e) {
        t = e;
        // Task handling the interrupt flag must throw CancellationException.
        setTaskState(TaskInfo.State.Aborted);
        writeTaskStateMetric(taskType, taskStartTime, TaskInfo.State.Aborted);
      } catch (Exception e) {
        // Propagate error to the task invoker.
        t = e;
        setTaskState(TaskInfo.State.Failure);
        writeTaskStateMetric(taskType, taskStartTime, TaskInfo.State.Failure);
      } finally {
        taskCompletionTime = Instant.now();
        // TODO remove when AbstractTaskBase is fixed.
        task.terminate();
        if (t != null) {
          updateTaskDetailsOnError(t);
          Throwables.propagate(t);
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

    public synchronized void saveTask() {
      taskInfo.save();
    }

    public synchronized TaskInfo.State getTaskState() {
      return taskInfo.getTaskState();
    }

    public synchronized void setTaskState(TaskInfo.State state) {
      taskInfo.setTaskState(state);
      taskInfo.save();
    }

    public synchronized void setTaskDetails(JsonNode taskDetails) {
      taskInfo.setTaskDetails(taskDetails);
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

    public void updateTaskDetailsOnError(Throwable t) {
      if (t == null) {
        return;
      }
      JsonNode taskDetails = taskInfo.getTaskDetails();
      String errorString =
          "Failed to execute task "
              + StringUtils.abbreviate(taskDetails.toString(), 500)
              + ", hit error:\n\n"
              + StringUtils.abbreviateMiddle(t.getMessage(), "...", 3000)
              + ".";

      log.error(
          "Failed to execute task type {} UUID {} details {}, hit error.",
          taskInfo.getTaskType(),
          taskInfo.getTaskUUID(),
          taskDetails,
          t);

      ObjectNode details = taskDetails.deepCopy();
      details.put("errorString", errorString);
      taskInfo.setTaskDetails(details);
      taskInfo.save();
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("task-info {" + taskInfo.toString() + "}");
      sb.append(", ");
      sb.append("task {" + task.getName() + "}");
      return sb.toString();
    }
  }

  /** Task runner */
  public class TaskRunner extends TaskRunnerBase {

    TaskRunner(ITask task, TaskInfo taskInfo) {
      super(task, taskInfo);
    }

    @Override
    public void run() {
      UUID taskUUID = taskInfo.getTaskUUID();
      currentTaskUUID.set(taskUUID);
      try {
        task.setUserTaskUUID(taskUUID);
        super.run();
      } finally {
        // Remove the task context
        tasksContexts.remove(taskUUID);
        // Cleanup is required for threads in a pool
        currentTaskUUID.remove();
        // Update the customer task to a completed state.
        CustomerTask customerTask = CustomerTask.findByTaskUUID(taskUUID);
        if (customerTask != null) {
          customerTask.markAsCompleted();
        }

        // In case it was a scheduled task, update state of the task.
        ScheduleTask scheduleTask = ScheduleTask.fetchByTaskUUID(taskUUID);
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

  /** Task runner for subtasks within a task. */
  public class SubTaskRunner extends TaskRunnerBase {

    SubTaskRunner(ITask task, TaskInfo taskInfo) {
      super(task, taskInfo);
    }

    @Override
    public void run() {
      // Make the context key available in the subtask thread
      currentTaskUUID.set(taskInfo.getParentUUID());
      try {
        super.run();
      } finally {
        // Cleanup is required for threads in a pool
        currentTaskUUID.remove();
      }
    }
  }
}
