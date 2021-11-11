package com.yugabyte.yw.commissioner.tasks.subtasks;

import javax.inject.Inject;

import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.tasks.params.NodeTaskParams;
import com.yugabyte.yw.commissioner.tasks.subtasks.SetNodeState.Params;
import com.yugabyte.yw.common.NodeManager;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.NodeStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SetNodeStatus extends NodeTaskBase {

  @Inject
  protected SetNodeStatus(BaseTaskDependencies baseTaskDependencies, NodeManager nodeManager) {
    super(baseTaskDependencies, nodeManager);
  }

  public static class Params extends NodeTaskParams {}

  protected Params taskParams() {
    return (Params) taskParams;
  }

  @Override
  public String toString() {
    return super.getName()
        + "("
        + taskParams().nodeName
        + ", "
        + taskParams().targetNodeStatus
        + ")";
  }

  @Override
  public void run() {
    try {
      log.info(
          "Updating node {} state to {} in universe {}.",
          taskParams().nodeName,
          taskParams().targetNodeStatus,
          taskParams().universeUUID);
      setNodeStatus(taskParams().targetNodeStatus);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
