// Copyright (c) YugaByte, Inc.
package com.yugabyte.yw.models.helpers;

import com.yugabyte.yw.models.helpers.NodeDetails.MasterState;
import com.yugabyte.yw.models.helpers.NodeDetails.NodeState;
import com.yugabyte.yw.models.helpers.NodeDetails.NodeSubState;
import com.yugabyte.yw.models.helpers.NodeDetails.TserverState;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@EqualsAndHashCode
@ToString
@Getter
public class NodeStatus {
  private final NodeState nodeState;
  private final NodeSubState nodeSubState;
  private final MasterState masterState;
  private final TserverState tserverState;

  public static class NodeStatusBuilder {

    private NodeState nodeState;
    private NodeSubState nodeSubState;
    private MasterState masterState;
    private TserverState tserverState;

    public NodeStatusBuilder setNodeState(NodeState nodeState) {
      this.nodeState = nodeState;
      return this;
    }

    public NodeStatusBuilder setNodeSubState(NodeSubState nodeSubState) {
      this.nodeSubState = nodeSubState;
      return this;
    }

    public NodeStatusBuilder setMasterState(MasterState masterState) {
      this.masterState = masterState;
      return this;
    }

    public NodeStatusBuilder setTServerState(TserverState tserverState) {
      this.tserverState = tserverState;
      return this;
    }

    public NodeStatus build() {
      if (nodeSubState == null) {
        nodeSubState = NodeSubState.None;
      }
      if (masterState == null) {
        masterState = MasterState.None;
      }
      if (tserverState == null) {
        tserverState = TserverState.None;
      }
      return new NodeStatus(nodeState, nodeSubState, masterState, tserverState);
    }
  }

  private NodeStatus(
      NodeState nodeState,
      NodeSubState nodeSubState,
      MasterState masterState,
      TserverState tserverState) {
    this.nodeState = nodeState;
    this.nodeSubState = nodeSubState;
    this.masterState = masterState;
    this.tserverState = tserverState;
  }

  public static NodeStatus fromNode(NodeDetails node) {
    return new NodeStatusBuilder()
        .setNodeState(node.state)
        .setNodeSubState(node.subState)
        .setMasterState(node.masterState)
        .setTServerState(node.tserverState)
        .build();
  }

  public void fillNodeStates(NodeDetails node) {
    if (nodeState != null) {
      node.state = nodeState;
    }
    // None means unset the state.
    if (nodeSubState != null) {
      node.subState = (nodeSubState == NodeSubState.None) ? null : nodeSubState;
    }
    if (masterState != null) {
      node.masterState = (masterState == MasterState.None) ? null : masterState;
    }
    if (tserverState != null) {
      node.tserverState = (tserverState == TserverState.None) ? null : tserverState;
    }
  }

  public static void copyStates(NodeDetails fromNode, NodeDetails toNode) {
    NodeStatus.fromNode(fromNode).fillNodeStates(toNode);
  }
}
