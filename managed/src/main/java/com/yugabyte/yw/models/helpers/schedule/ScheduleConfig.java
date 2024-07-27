// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.models.helpers.schedule;

import io.swagger.annotations.ApiModelProperty;
import java.time.Duration;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder(toBuilder = true)
@Jacksonized
public class ScheduleConfig {
  @ApiModelProperty @Builder.Default private ScheduleType type = ScheduleType.FIXED_DELAY;
  @ApiModelProperty @Builder.Default private Duration interval = Duration.ofMinutes(1);
  private boolean disabled;

  public enum ScheduleType {
    FIXED_DELAY,
    FIXED_RATE
  }
}
