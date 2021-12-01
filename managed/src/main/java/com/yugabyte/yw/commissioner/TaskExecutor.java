// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner;

import static com.yugabyte.yw.models.helpers.CommonUtils.getDurationSeconds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.util.Throwables;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.google.gdata.util.common.base.Preconditions;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
import java.util.concurrent.atomic.AtomicReference;
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
        .labels(taskType.name(), state.name())
        .observe(getDurationSeconds(startTime, Instant.now()));
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

  public UUID submit(TaskRunner taskRunner, ExecutorService taskExecutorService) {
    return submit(taskRunner, taskExecutorService, null);
  }

  // Submit a task to the task executor with a specific executor service and a task execution
  // listener.
  public UUID submit(
      TaskRunner taskRunner,
      ExecutorService taskExecutorService,
      TaskExecutionListener taskExecutionListener) {
    checkTaskExecutorState();
    Preconditions.checkNotNull(taskRunner, "Task runner must not be null");
    Preconditions.checkNotNull(taskExecutorService, "Task executor service must not be null");
    // Task must be saved right before submitting to prevent
    // tasks from dangling due to shutdown of the task executor.
    taskRunner.saveTask();
    UUID taskUUID = taskRunner.getTaskUUID();
    TaskContext taskContext = new TaskContext(taskRunner, taskExecutionListener);
    tasksContexts.put(taskUUID, taskContext);
    try {
      taskRunner.updateScheduledTime();
      taskRunner.future = taskExecutorService.submit(taskRunner);
    } catch (Exception e) {
      // Update task state on submission failure.
      tasksContexts.remove(taskUUID);
      taskRunner.setTaskState(TaskInfo.State.Failure);
      taskRunner.updateTaskDetailsOnError(e);
    }
    return taskUUID;
  }

  // This waits for the parent task to complete indefinitely.
  public void waitForTask(UUID taskUUID) {
    waitForTask(taskUUID, null);
  }

  // This waits for the parent task to complete within the timeout.
  public void waitForTask(UUID taskUUID, Duration timeout) {
    Optional<TaskContext> optional = getCurrentTaskContext();
    if (optional.isPresent()) {
      throw new IllegalStateException("waitForTask must not be called from task");
    }
    TaskContext taskContext = tasksContexts.get(taskUUID);
    if (taskContext == null || taskContext.taskRunner.future == null) {
      return;
    }
    try {
      if (timeout == null || timeout.isZero()) {
        taskContext.taskRunner.future.get();
      } else {
        taskContext.taskRunner.future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
    TaskContext taskContext = mustGetCurrentTaskContext();
    taskContext.taskRunner.setTaskDetails(
        RedactingService.filterSecretFields(task.getTaskDetails()));
  }

  public boolean isTaskRunning(UUID taskUUID) {
    return tasksContexts.containsKey(taskUUID);
  }

  // Signal to abort the task if it is already running.
  // It does not stop the task immediately.
  public Optional<TaskInfo> abort(UUID taskUUID, boolean forcefully) {
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

  // Add a subtask for asynchronous execution from the parent task.
  public void addAsyncSubTask(ITask subTask) {
    log.info("addAsyncSubTask called for {}", subTask);
    TaskContext taskContext = mustGetCurrentTaskContext();
    TaskInfo taskInfo = createTaskInfo(subTask);
    taskInfo.setPosition(taskContext.position);
    taskInfo.setParentUuid(taskContext.taskRunner.getTaskUUID());
    taskInfo.setSubTaskGroupType(taskContext.subTaskGroupType);
    taskInfo.save();
    SubTaskRunner taskRunner = new SubTaskRunner(subTask, taskInfo);
    taskContext.subTaskRunners.add(taskRunner);
  }

  // Submit a subtask task for synchronous execution
  // from the parent task.
  public void runSyncSubTask(ITask subTask) {
    log.info("runSyncSubTask called for {}", subTask);
    TaskContext taskContext = mustGetCurrentTaskContext();
    if (taskContext.subTaskRunners.size() > 0) {
      // This prevents calling of this method after async.
      throw new IllegalStateException(
          "runSyncSubTask cannot be called when there are pending tasks");
    }
    ExecutorService executorService = getSubTaskExecutorService(taskContext);
    TaskInfo taskInfo = createTaskInfo(subTask);
    taskInfo.setPosition(taskContext.position);
    taskInfo.setParentUuid(taskContext.taskRunner.getTaskUUID());
    taskInfo.setSubTaskGroupType(taskContext.subTaskGroupType);
    taskInfo.save();
    SubTaskRunner subTaskRunner = new SubTaskRunner(subTask, taskInfo);
    try {
      taskContext.subTaskRunners.add(subTaskRunner);
      try {
        subTaskRunner.updateScheduledTime();
        subTaskRunner.future = executorService.submit(subTaskRunner);
      } catch (RuntimeException e) {
        // Submission must have failed.
        subTaskRunner.setTaskState(TaskInfo.State.Failure);
        subTaskRunner.updateTaskDetailsOnError(e);
        throw e;
      }
      waitForSubTasks();
    } finally {
      taskContext.subTaskRunners.clear();
    }
  }

  // Submits the previously added asynchronous subtasks to the thread pool
  // and waits for the executions to complete.
  public void runAsyncSubTasks() {
    runAsyncSubTasks(null);
  }

  // Submits the previously added asynchronous subtasks to the thread pool
  // and waits for the executions to complete with a given task execution
  // listener.
  public void runAsyncSubTasks(TaskExecutionListener listener) {
    TaskContext taskContext = mustGetCurrentTaskContext();
    ExecutorService executorService = getSubTaskExecutorService(taskContext);
    RuntimeException anyRe = null;
    try {
      if (listener != null) {
        taskContext.taskExecutionListeners.add(listener);
      }
      for (SubTaskRunner subTaskRunner : taskContext.subTaskRunners) {
        try {
          subTaskRunner.updateScheduledTime();
          subTaskRunner.future = executorService.submit(subTaskRunner);
        } catch (RuntimeException e) {
          // Subtask submission failed.
          anyRe = e;
          subTaskRunner.setTaskState(TaskInfo.State.Failure);
          subTaskRunner.updateTaskDetailsOnError(e);
          publishAfterTask(taskContext, subTaskRunner.taskInfo, e);
          break;
        }
      }
      waitForSubTasks();
      if (anyRe != null) {
        throw anyRe;
      }
    } finally {
      taskContext.subTaskRunners.clear();
      if (listener != null) {
        taskContext.taskExecutionListeners.remove(listener);
      }
    }
  }

  public void setSubTaskGroupType(SubTaskGroupType subTaskGroupType) {
    if (subTaskGroupType == null) {
      return;
    }
    log.info("Setting subtask group type to {}", subTaskGroupType);
    TaskContext taskContext = mustGetCurrentTaskContext();
    taskContext.subTaskGroupType = subTaskGroupType;
    for (SubTaskRunner subTaskRunner : taskContext.subTaskRunners) {
      subTaskRunner.setSubTaskGroupType(subTaskGroupType);
    }
  }

  public void setSubTaskExecutor(ExecutorService executorService) {
    TaskContext taskContext = mustGetCurrentTaskContext();
    taskContext.subTaskExecutorService.set(executorService);
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

  // Gets the current task context by looking up the task UUID in the thread local.
  private Optional<TaskContext> getCurrentTaskContext() {
    TaskContext taskContext = null;
    UUID taskUUID = currentTaskUUID.get();
    if (taskUUID == null || (taskContext = tasksContexts.get(taskUUID)) == null) {
      return Optional.empty();
    }
    return Optional.of(taskContext);
  }

  // Gets the current task context by looking up the task UUID in the thread local.
  // Throws exception if the context is not present.
  private TaskContext mustGetCurrentTaskContext() {
    Optional<TaskContext> optional = getCurrentTaskContext();
    return optional.orElseThrow(
        () -> new IllegalStateException("Method must be called from the task run method"));
  }

  // Wait for all the subtasks to complete. In this method, the state updates on
  // exceptions are done for tasks which are not yet running.
  private void waitForSubTasks() {
    TaskContext taskContext = mustGetCurrentTaskContext();
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
          publishAfterTask(taskContext, subTaskRunner.taskInfo, null);
        } catch (ExecutionException e) {
          t = e.getCause();
        } catch (TimeoutException e) {
          Duration elapsed = Duration.between(waitStartTime, Instant.now());
          if (log.isTraceEnabled()) {
            log.trace("Task {} has taken {}ms", subTaskRunner.getTaskUUID(), elapsed.toMillis());
          }
          Duration timeout = subTaskRunner.getTimeLimit();
          // If the subtask execution takes long, it is interrupted.
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
            iter.remove();
            anyEx = t;
            publishAfterTask(taskContext, subTaskRunner.taskInfo, t);
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

  private ExecutorService getSubTaskExecutorService(TaskContext taskContext) {
    return taskContext.subTaskExecutorService.updateAndGet(
        e -> {
          if (e == null) {
            return executorServiceProvider.getExecutorService(taskContext.taskRunner.getTaskType());
          }
          return e;
        });
  }

  private void publishBeforeTask(TaskContext taskContext, TaskInfo taskInfo) {
    taskContext.taskExecutionListeners.stream().forEach(l -> l.beforeTask(taskInfo));
  }

  private void publishAfterTask(TaskContext taskContext, TaskInfo taskInfo, Throwable t) {
    taskContext.taskExecutionListeners.stream().forEach(l -> l.afterTask(taskInfo, t));
  }

  /**
   * Listener to get called when a task is executed. This is useful if a task needs to be aborted
   * before its execution. Throwing CancellationException in beforeTask aborts the task.
   */
  @FunctionalInterface
  public interface TaskExecutionListener {
    default void beforeTask(TaskInfo taskInfo) {};

    void afterTask(TaskInfo taskInfo, Throwable t);
  }

  /** TaskContext holds the information of a running submitted task */
  private static class TaskContext {
    private final Set<SubTaskRunner> subTaskRunners =
        Collections.newSetFromMap(new ConcurrentHashMap<SubTaskRunner, Boolean>());

    private final TaskRunner taskRunner;

    private final Set<TaskExecutionListener> taskExecutionListeners;

    private final AtomicReference<ExecutorService> subTaskExecutorService;

    private volatile Instant abortTime;

    private SubTaskGroupType subTaskGroupType;

    // Execution position of subtasks.
    private int position = 0;

    TaskContext(TaskRunner taskRunner, TaskExecutionListener taskExecutionListener) {
      this.taskRunner = taskRunner;
      this.taskExecutionListeners = Collections.newSetFromMap(new IdentityHashMap<>());
      this.subTaskExecutorService = new AtomicReference<>();
      this.position = taskRunner.getTaskPosition() + 1;
      this.subTaskGroupType = UserTaskDetails.SubTaskGroupType.Invalid;
      if (taskExecutionListener != null) {
        this.taskExecutionListeners.add(taskExecutionListener);
      }
    }
  }

  /**
   * Base implementation of a task runner which handles the state update after the task has started
   * running.
   */
  public class TaskRunnerBase implements Runnable {
    final ITask task;
    final TaskInfo taskInfo;
    final Duration timeLimit;

    Instant taskScheduledTime;
    Instant taskStartTime;
    Instant taskCompletionTime;

    // Future of the task that is set after it is submitted to the ExecutorService.
    Future<?> future = null;

    protected TaskRunnerBase(ITask task, TaskInfo taskInfo) {
      this.task = task;
      this.taskInfo = taskInfo;
      this.taskScheduledTime = Instant.now();

      Duration duration = Duration.ZERO;
      try {
        JsonNode jsonNode = (JsonNode) taskInfo.getTaskDetails();
        JsonNode timeLimitJsonNode = jsonNode.get("timeLimitMins");
        if (timeLimitJsonNode != null && !timeLimitJsonNode.isNull()) {
          long timeLimitMins = Long.parseLong(timeLimitJsonNode.asText());
          duration = Duration.ofMinutes(timeLimitMins);
        }
      } catch (RuntimeException e) {
      }
      timeLimit = duration;
    }

    @Override
    public void run() {
      Throwable t = null;
      TaskType taskType = taskInfo.getTaskType();
      taskStartTime = Instant.now();
      try {
        writeTaskWaitMetric(taskType, taskInfo.getCreationTime().toInstant());
        TaskContext taskContext = mustGetCurrentTaskContext();
        if (taskContext.abortTime != null) {
          throw new CancellationException("Task " + task.getName() + " is aborted");
        }
        publishBeforeTask(taskContext, taskInfo);
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
        t = e;
        setTaskState(TaskInfo.State.Failure);
        writeTaskStateMetric(taskType, taskStartTime, TaskInfo.State.Failure);
      } finally {
        taskCompletionTime = Instant.now();
        task.terminate();
        if (t != null) {
          updateTaskDetailsOnError(t);
          Throwables.propagate(t);
        }
      }
    }

    public Duration getTimeLimit() {
      return timeLimit;
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
      Throwable t = null;
      UUID taskUUID = taskInfo.getTaskUUID();
      currentTaskUUID.set(taskUUID);
      try {
        task.setUserTaskUUID(taskUUID);
        super.run();
      } catch (Exception e) {
        t = e;
        Throwables.propagate(e);
      } finally {
        // Remove the task context
        TaskContext taskContext = tasksContexts.remove(taskUUID);
        // Cleanup is required for threads in a pool
        currentTaskUUID.remove();
        // Update the customer task to a completed state.
        CustomerTask customerTask = CustomerTask.findByTaskUUID(taskUUID);
        if (customerTask != null) {
          customerTask.markAsCompleted();
        }

        // In case, it is a scheduled task, update state of the task.
        ScheduleTask scheduleTask = ScheduleTask.fetchByTaskUUID(taskUUID);
        if (scheduleTask != null) {
          scheduleTask.setCompletedTime();
        }
        // Run a one-off Platform HA sync every time a task finishes.
        replicationManager.oneOffSync();
        // Publish completion for the parent task.
        publishAfterTask(taskContext, taskInfo, t);
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

    public synchronized void setSubTaskGroupType(SubTaskGroupType subTaskGroupType) {
      if (taskInfo.getSubTaskGroupType() != subTaskGroupType) {
        taskInfo.setSubTaskGroupType(subTaskGroupType);
        taskInfo.save();
      }
    }
  }
}
