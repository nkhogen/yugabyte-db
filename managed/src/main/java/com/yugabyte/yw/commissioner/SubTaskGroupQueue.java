// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
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
    try {
      RuntimeException anyEx = null;
      for (SubTaskGroup subTaskGroup : subTaskGroups) {
        RuntimeException re = null;
        try {
          subTaskGroup.run();
          subTaskGroup.waitFor();
        } catch (RuntimeException e) {
          re = e;
          anyEx = re;
          log.error("Error occurred in " + subTaskGroup.toString(), e);
        }
        if (re != null && !subTaskGroup.ignoreErrors) {
          LOG.error("SubTaskGroup '{}' waitFor() returned failed status.", subTaskGroup.toString());
          if (re instanceof CancellationException) {
            throw new CancellationException(subTaskGroup.toString() + " is cancelled.");
          }
          if (!subTaskGroup.ignoreErrors) {
            throw new RuntimeException(subTaskGroup.toString() + " failed.");
          }
        }
      }
      if (anyEx != null) {
        if (anyEx instanceof CancellationException) {
          throw new CancellationException("SubTask is cancelled.");
        }
        throw new RuntimeException("One or more subTaskGroups failed while running.");
      }
    } finally {
      subTaskGroups.clear();
    }
  }
}
