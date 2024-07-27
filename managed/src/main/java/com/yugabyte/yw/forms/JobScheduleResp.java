// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.forms;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.yugabyte.yw.models.JobSchedule;
import io.swagger.annotations.ApiModel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ApiModel(description = "Job schedule details")
public class JobScheduleResp {
  @JsonUnwrapped private final JobSchedule jobSchedule;

  public JobScheduleResp(JobSchedule jobSchedule) {
    this.jobSchedule = jobSchedule;
  }
}
