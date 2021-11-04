// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner;

import static com.yugabyte.yw.models.helpers.CommonUtils.getDurationSeconds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yugabyte.yw.common.password.RedactingService;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.TaskInfo.State;
import com.yugabyte.yw.models.helpers.TaskType;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import play.api.Play;

import org.apache.commons.lang3.StringUtils;

@Slf4j
public class SubTaskGroup implements Runnable {

  private final TaskExecutor taskExecutor;

  private List<ITask> tasks = new ArrayList<>();

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
   * @param executor : The threadpool to run the task on.
   * @param ignoreErrors : Flag to tell if an error needs to be thrown if the subTask fails.
   */
  public SubTaskGroup(String name, ExecutorService executor, boolean ignoreErrors) {
    this.name = name;
    this.numTasksCompleted = new AtomicInteger(0);
    this.ignoreErrors = ignoreErrors;
    this.taskExecutor = Play.current().injector().instanceOf(TaskExecutor.class);
  }

  public synchronized void setSubTaskGroupType(UserTaskDetails.SubTaskGroupType subTaskGroupType) {
    this.taskExecutor.setSubTaskGroupType(subTaskGroupType);
  }

  public synchronized void setUserSubTaskState(TaskInfo.State userTaskState) {
    taskExecutor.setSubTaskState(userTaskState);
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
   * Asynchronously starts the tasks and returns. To wait for the tasks to complete, call the
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
      taskExecutor.async(task);
    }
  }

  public boolean waitFor() {
    boolean hasErrored = false;
    try {
      TaskInfo taskInfo = taskExecutor.getCurrentTaskInfo();
      Duration duration = Duration.ZERO;
      if (taskInfo.getTaskType() == TaskType.RunExternalScript) {
        JsonNode jsonNode = (JsonNode) taskInfo.getTaskDetails();
        long timeLimitMins = Long.parseLong(jsonNode.get("timeLimitMins").asText());
        duration = Duration.ofMinutes(timeLimitMins);
      }
      // Wait for all the submitted tasks in the run() method.
      taskExecutor.waitAsyncTasks(
          duration,
          (t, e) -> {
            numTasksCompleted.incrementAndGet();
          });
    } catch (Exception e) {
      hasErrored = true;
    }
    return !hasErrored;
  }
}
