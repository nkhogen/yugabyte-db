// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.forms.filters;

import com.yugabyte.yw.models.filters.JobScheduleFilter;
import com.yugabyte.yw.models.helpers.schedule.ScheduleConfig.ScheduleType;
import lombok.Data;

@Data
public class JobScheduleApiFilter {
  private String nameRegex;
  private String configClass;
  private ScheduleType type;
  private long nextStartWindowSecs;
  private boolean enabledOnly;

  public JobScheduleFilter toFilter() {
    return JobScheduleFilter.builder()
        .nameRegex(nameRegex)
        .configClass(configClass)
        .type(type)
        .nextStartWindowSecs(nextStartWindowSecs)
        .enabledOnly(enabledOnly)
        .build();
  }
}
