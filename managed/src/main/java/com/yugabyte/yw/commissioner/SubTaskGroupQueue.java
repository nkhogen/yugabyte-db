// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner;

import com.yugabyte.yw.models.TaskInfo;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Slf4j
public class SubTaskGroupQueue {

  public static final Logger LOG = LoggerFactory.getLogger(SubTaskGroupQueue.class);

  // TODO unused field to be removed.
  private final UUID userTaskUUID;

  // The list of tasks lists in this task list sequence.
  private final CopyOnWriteArrayList<SubTaskGroup> subTaskGroups =
      new CopyOnWriteArrayList<SubTaskGroup>();

  public SubTaskGroupQueue(UUID userTaskUUID) {
    this.userTaskUUID = userTaskUUID;
  }

  /** Add a task list to this sequence. */
  public boolean add(SubTaskGroup subTaskGroup) {
    log.info("Adding subTaskGroup #{}: {}", subTaskGroups.size(), subTaskGroup.getName());
    return subTaskGroups.add(subTaskGroup);
  }

  /** Execute the sequence of task lists in a sequential manner. */
  public void run() {
    boolean runSuccess = true;
    try {
      for (SubTaskGroup subTaskGroup : subTaskGroups) {
        boolean subTaskGroupSuccess = false;
        subTaskGroup.setUserSubTaskState(TaskInfo.State.Running);
        try {
          subTaskGroup.run();
          subTaskGroupSuccess = subTaskGroup.waitFor();
        } catch (Throwable t) {
          // Update task state to failure
          subTaskGroup.setUserSubTaskState(TaskInfo.State.Failure);
          if (!subTaskGroup.ignoreErrors) {
            throw t;
          }
        }

        if (!subTaskGroupSuccess) {
          LOG.error("SubTaskGroup '{}' waitFor() returned failed status.", subTaskGroup.toString());
          if (!subTaskGroup.ignoreErrors) {
            throw new RuntimeException(subTaskGroup.toString() + " failed.");
          }
        }

        runSuccess = runSuccess && subTaskGroupSuccess;

        if (subTaskGroupSuccess) subTaskGroup.setUserSubTaskState(TaskInfo.State.Success);
      }

      if (!runSuccess)
        throw new RuntimeException("One or more subTaskGroups failed while running.");
    } finally {
      subTaskGroups.clear();
    }
  }
}
