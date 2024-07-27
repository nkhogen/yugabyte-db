// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.forms.filters;

import com.yugabyte.yw.models.JobInstance.State;
import com.yugabyte.yw.models.filters.JobInstanceFilter;
import lombok.Data;

@Data
public class JobInstanceApiFilter {
  private State state;
  private long startWindowSecs;

  public JobInstanceFilter toFilter() {
    return JobInstanceFilter.builder().state(state).startWindowSecs(startWindowSecs).build();
  }
}
