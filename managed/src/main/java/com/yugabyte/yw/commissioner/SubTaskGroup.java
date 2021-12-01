// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.client.util.Throwables;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.common.password.RedactingService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import play.api.Play;

@Slf4j
public class SubTaskGroup implements Runnable {

  private final TaskExecutor taskExecutor;

  private final ExecutorService executorService;

  private final List<ITask> tasks;

  // Task list name.
  private final String name;

  private final AtomicInteger numTasksCompleted;

  // Flag to denote if an exception needs to be thrown on failure.
  final boolean ignoreErrors;

  /**
   * Creates the task list.
   *
   * @param name : Name for the task list, used to name the threads.
   * @param executor : The threadpool to run the task on.
   */
  public SubTaskGroup(String name, ExecutorService executor) {
    this(name, executor, false);
  }

  /**
   * Creates the task list.
   *
   * @param name : Name for the task list, used to name the threads.
   * @param executorService : The threadpool to run the task on.
   * @param ignoreErrors : Flag to tell if an error needs to be thrown if the subTask fails.
   */
  public SubTaskGroup(String name, ExecutorService executorService, boolean ignoreErrors) {
    this.name = name;
    this.executorService = executorService;
    this.ignoreErrors = ignoreErrors;
    this.numTasksCompleted = new AtomicInteger(0);
    this.taskExecutor = Play.current().injector().instanceOf(TaskExecutor.class);
    this.tasks = new ArrayList<>();
  }

  public synchronized void setSubTaskGroupType(SubTaskGroupType subTaskGroupType) {
    taskExecutor.setSubTaskGroupType(subTaskGroupType);
  }

  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return getName() + " : completed " + getNumTasksDone() + " out of " + getNumTasks() + " tasks.";
  }

  public void addTask(AbstractTaskBase task) {
    int size = tasks.size();
    log.info("Adding task #{}: {}", size, task.getName());
    if (log.isDebugEnabled()) {
      JsonNode redactedTask = RedactingService.filterSecretFields(task.getTaskDetails());
      log.debug("Details for task #{}: {} details= {}", size, task.getName(), redactedTask);
    }
    tasks.add(task);
  }

  public int getNumTasks() {
    return tasks.size();
  }

  public int getNumTasksDone() {
    return numTasksCompleted.get();
  }

  /**
   * Asynchronously adds the tasks and returns. To run and wait for the tasks to complete, call the
   * waitFor() method.
   */
  @Override
  public void run() {
    // Submit the tasks to the task executor to start running.
    for (ITask task : tasks) {
      if (log.isDebugEnabled()) {
        JsonNode redactedTask = RedactingService.filterSecretFields(task.getTaskDetails());
        log.debug("Running task {} details= {}", task.getName(), redactedTask);
      }
      taskExecutor.addAsyncSubTask(task);
    }
  }

  public void waitFor() {
    try {
      taskExecutor.setSubTaskExecutor(executorService);
      // Wait for all the submitted tasks in the run() method.
      taskExecutor.runAsyncSubTasks(
          (t, e) -> {
            numTasksCompleted.incrementAndGet();
          });
    } catch (Exception e) {
      Throwables.propagate(e);
    }
  }
}
