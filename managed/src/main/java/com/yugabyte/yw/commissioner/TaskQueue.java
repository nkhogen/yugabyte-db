// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner;

import static com.google.common.base.Preconditions.checkState;

import com.cronutils.utils.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Singleton;
import com.yugabyte.yw.commissioner.TaskExecutor.RunnableTask;
import com.yugabyte.yw.commissioner.TaskExecutor.TaskParams;
import com.yugabyte.yw.commissioner.TaskQueue.Queue.OpType;
import com.yugabyte.yw.common.PlatformExecutorFactory;
import com.yugabyte.yw.common.ShutdownHookHandler;
import com.yugabyte.yw.common.TaskExecutionException;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.common.concurrent.KeyLock;
import com.yugabyte.yw.common.config.GlobalConfKeys;
import com.yugabyte.yw.common.config.RuntimeConfGetter;
import com.yugabyte.yw.common.logging.LogUtil;
import com.yugabyte.yw.forms.ITaskParams;
import com.yugabyte.yw.models.TaskInfo.State;
import com.yugabyte.yw.models.helpers.YBAError.Code;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/** Queue for tasks for each resource target. */
@Slf4j
@Singleton
public class TaskQueue {
  private static final int DEFAULT_QUEUE_CAPACITY = 2;

  private final ShutdownHookHandler shutdownHookHandler;
  private final RuntimeConfGetter runtimeConfGetter;
  private final PlatformExecutorFactory platformExecutorFactory;
  private final int capacity;

  // Target UUID (e.g universe UUID) to Queue of tasks.
  private final Map<UUID, Queue> targetTaskQueues = new ConcurrentHashMap<>();
  // All pending tasks waiting in the queue for expiry.
  private final DelayQueue<Node> expiryTaskQueue = new DelayQueue<>();
  // Task UUID to target UUIDs.
  private final Map<UUID, UUID> taskTargets = new ConcurrentHashMap<>();
  // Key lock for targets.
  private final KeyLock<UUID> targetKeyLock = new KeyLock<>();
  // Executor for garbage collection.
  private ExecutorService garbageCollectorExecutor;

  @Inject
  public TaskQueue(
      ShutdownHookHandler shutdownHookHandler,
      RuntimeConfGetter runtimeConfGetter,
      PlatformExecutorFactory platformExecutorFactory) {
    this(shutdownHookHandler, runtimeConfGetter, platformExecutorFactory, DEFAULT_QUEUE_CAPACITY);
  }

  public TaskQueue(
      ShutdownHookHandler shutdownHookHandler,
      RuntimeConfGetter runtimeConfGetter,
      PlatformExecutorFactory platformExecutorFactory,
      int capacity) {
    this.shutdownHookHandler = shutdownHookHandler;
    this.runtimeConfGetter = runtimeConfGetter;
    this.platformExecutorFactory = platformExecutorFactory;
    this.capacity = capacity;
  }

  public void init() {
    garbageCollectorExecutor =
        platformExecutorFactory.createFixedExecutor(
            getClass().getSimpleName(),
            1,
            new ThreadFactoryBuilder().setNameFormat("Comissioner-TaskQueue-%s").build());
    garbageCollectorExecutor.submit(
        () -> {
          while (!shutdownHookHandler.isShutdown()) {
            try {
              Node node = runGarbageCollection(true /* wait */);
              if (node != null) {
                log.debug("Garbage collected task {}", node.taskRunnable.getTaskType());
              }
            } catch (Exception e) {
              log.error("Error in running garbage collection - " + e.getMessage());
            }
          }
        });
    shutdownHookHandler.addShutdownHook(
        garbageCollectorExecutor,
        exec -> {
          if (exec != null) {
            exec.shutdownNow();
          }
        },
        99 /* weight */);
  }

  // Minimal linked list to allow removal by node directly.
  static class Queue {
    private final BiConsumer<Node, OpType> listener;
    private final int capacity;

    private Node head;
    private Node tail;
    private int size;

    enum OpType {
      ADD,
      REMOVE
    }

    private Queue(int capacity, BiConsumer<Node, OpType> listener) {
      this.capacity = capacity;
      this.listener = listener;
    }

    synchronized int size() {
      return size;
    }

    synchronized void ensureCapacity() {
      if (capacity <= size) {
        throw new IllegalStateException("Queue is already full with max capacity " + capacity);
      }
    }

    synchronized boolean add(Node node) {
      checkState(node != null, "Node cannot be null");
      ensureCapacity();
      node.next = null;
      node.previous = null;
      if (head == null || tail == null) {
        head = node;
        tail = node;
      } else {
        tail.next = node;
        node.previous = tail;
        tail = node;
      }
      node.isMember = true;
      size++;
      listener.accept(node, OpType.ADD);
      return true;
    }

    synchronized Node peek() {
      return head;
    }

    synchronized boolean remove(Node node) {
      checkState(size > 0, "Size must be non-zero");
      if (node == null || !node.isMember || size <= 0) {
        return false;
      }
      if (node == head) {
        head = node.next;
      }
      if (node == tail) {
        tail = node.previous;
      }
      if (node.previous != null) {
        node.previous.next = node.next;
      }
      if (node.next != null) {
        node.next.previous = node.previous;
      }
      node.isMember = false;
      node.previous = null;
      node.next = null;
      size--;
      listener.accept(node, OpType.REMOVE);
      return true;
    }

    synchronized void remove(Predicate<Node> predicate) {
      Node node = head;
      while (node != null) {
        checkState(size > 0, "Size must be non-zero");
        if (predicate.test(node)) {
          remove(node);
        }
        node = node.next;
      }
    }
  }

  @VisibleForTesting
  static class Node implements Delayed {
    private volatile boolean isMember;
    private Node previous;
    private Node next;

    final RunnableTask taskRunnable;
    final ITaskParams taskParams;
    final String correlationId;
    final Instant expireAt;

    Node(
        RunnableTask taskRunnable, ITaskParams taskParams, String correlationId, Instant expireAt) {
      this.taskRunnable = taskRunnable;
      this.taskParams = taskParams;
      this.correlationId = correlationId;
      this.expireAt = expireAt;
    }

    @Override
    public int compareTo(Delayed o) {
      Node node = (Node) o;
      return expireAt.compareTo(node.expireAt);
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(
          expireAt.toEpochMilli() - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }
  }

  @VisibleForTesting
  Node runGarbageCollection(boolean wait) throws InterruptedException {
    log.debug("Running garbage collection at {}", Instant.now());
    Node node = wait ? expiryTaskQueue.take() : expiryTaskQueue.poll();
    if (node == null) {
      return null;
    }
    UUID targetUuid = taskTargets.get(node.taskRunnable.getTaskUUID());
    if (targetUuid == null) {
      return null;
    }
    targetKeyLock.acquireLock(targetUuid);
    try {
      if (node.taskRunnable.isRunning()) {
        return node;
      }
      Queue queue = targetTaskQueues.get(targetUuid);
      if (queue != null) {
        queue.remove(node);
      }
      node.taskRunnable.updateTaskDetailsOnError(
          State.Aborted, new TaskExecutionException(Code.TIMED_OUT, "Timed out in the queue"));
    } finally {
      targetKeyLock.releaseLock(targetUuid);
    }
    return node;
  }

  private Queue getOrCreateQueue(UUID targetUuid) {
    return targetTaskQueues.computeIfAbsent(
        targetUuid,
        k ->
            new Queue(
                this.capacity,
                (node, opType) -> {
                  if (opType == OpType.ADD) {
                    taskTargets.put(node.taskRunnable.getTaskUUID(), targetUuid);
                    expiryTaskQueue.add(node);
                  } else if (opType == OpType.REMOVE) {
                    expiryTaskQueue.remove(node);
                    taskTargets.remove(node.taskRunnable.getTaskUUID());
                  }
                }));
  }

  public int size(UUID targetUuid) {
    Queue queue = targetTaskQueues.get(targetUuid);
    return queue == null ? 0 : queue.size();
  }

  public RunnableTask enqueue(
      TaskParams taskParams,
      Function<TaskParams, RunnableTask> taskRunnnableFunction,
      BiConsumer<RunnableTask, ITaskParams> taskRunnableConsumer) {
    UUID targetUuid = taskParams.getTaskParams().getTargetUuid();
    if (targetUuid == null) {
      log.info("Unknown target for task {}. Queueing is not supported", taskParams.getTaskType());
      RunnableTask taskRunnable =
          Objects.requireNonNull(taskRunnnableFunction.apply(taskParams), "Runnable task is null");
      taskRunnableConsumer.accept(taskRunnable, taskParams.getTaskParams());
      return taskRunnable;
    }
    targetKeyLock.acquireLock(targetUuid);
    try {
      Queue queue = getOrCreateQueue(targetUuid);
      Node head = queue.size == 0 ? null : queue.peek();
      if (head != null) {
        ITask currentTask = head.taskRunnable.getTask();
        if (!currentTask.isQueueable(taskParams.getTaskType(), taskParams.getTaskParams())) {
          log.error(
              "Task {} is not queueable on existing task {}({})",
              taskParams.getTaskType(),
              head.taskRunnable.getTaskType(),
              head.taskRunnable.getTaskUUID());
          throw new IllegalStateException(
              String.format(
                  "Task %s is not queueable on existing task %s",
                  taskParams.getTaskType(), head.taskRunnable.getTaskType()));
        }
      }
      queue.ensureCapacity();
      RunnableTask taskRunnable = taskRunnnableFunction.apply(taskParams);
      String correlationId = MDC.get(LogUtil.CORRELATION_ID);
      if (correlationId == null) {
        correlationId = UUID.randomUUID().toString();
      }
      Duration evictionTimeout =
          runtimeConfGetter.getGlobalConf(GlobalConfKeys.runningTaskEvictionTimeout);
      Duration queuedTaskExpireAt = evictionTimeout.plus(evictionTimeout);
      Node node =
          new Node(
              taskRunnable,
              taskParams.getTaskParams(),
              correlationId,
              Instant.now().plus(queuedTaskExpireAt.toMillis(), ChronoUnit.MILLIS));
      // Always add to the queue to keep track of in-progress task too.
      log.info("Queueing task {} with time-out at {}", taskRunnable.getTaskType(), node.expireAt);
      queue.add(node);
      if (head == null) {
        taskRunnableConsumer.accept(taskRunnable, taskParams.getTaskParams());
      } else if (head.taskRunnable.isRunning()) {
        log.info(
            "Aborting the currently running task {} in {} secs",
            head.taskRunnable.getTaskType(),
            evictionTimeout.getSeconds());
        head.taskRunnable.abort(evictionTimeout);
      }
      return taskRunnable;
    } finally {
      targetKeyLock.releaseLock(targetUuid);
    }
  }

  public RunnableTask dequeue(
      RunnableTask completedTask, BiConsumer<RunnableTask, ITaskParams> taskRunnableConsumer) {
    UUID targetUuid = taskTargets.get(completedTask.getTaskUUID());
    if (targetUuid == null) {
      log.info(
          "Task {}({}) was not queued", completedTask.getTaskType(), completedTask.getTaskUUID());
    } else {
      targetKeyLock.acquireLock(targetUuid);
      try {
        Queue queue = targetTaskQueues.get(targetUuid);
        if (queue != null) {
          while (queue.size() > 0) {
            Node head = queue.peek();
            if (head.taskRunnable.hasTaskCompleted()) {
              log.debug("Removing completed task {}", completedTask.getTaskType());
              queue.remove(head);
            } else if (head.taskRunnable.isRunning()) {
              log.error(
                  "Unexpected running state found for task {}", head.taskRunnable.getTaskInfo());
              queue.remove(head);
              throw new IllegalStateException(
                  "Unexpected running state found for task " + head.taskRunnable.getTaskInfo());
            } else {
              log.debug("Submitting next task {}", head.taskRunnable.getTaskType());
              try {
                return Util.doWithCorrelationId(
                    head.correlationId,
                    id -> {
                      taskRunnableConsumer.accept(head.taskRunnable, head.taskParams);
                      return head.taskRunnable;
                    });
              } catch (Exception e) {
                queue.remove(head);
                log.error("Error in submitting task {}", head.taskRunnable.getTaskType());
              }
            }
          }
          if (queue.size() == 0) {
            targetTaskQueues.remove(targetUuid);
          }
        }
      } catch (Exception e) {
        log.error("Error in dequeing task for target {}", targetUuid, e);
      } finally {
        targetKeyLock.releaseLock(targetUuid);
      }
    }
    return null;
  }

  public void drain(UUID targetUuid) {
    targetKeyLock.acquireLock(targetUuid);
    try {
      Queue queue = targetTaskQueues.get(targetUuid);
      if (queue != null) {
        queue.remove(n -> !n.taskRunnable.isRunning());
        if (queue.size() == 0) {
          targetTaskQueues.remove(targetUuid);
        }
      }
    } finally {
      targetKeyLock.releaseLock(targetUuid);
    }
  }
}
