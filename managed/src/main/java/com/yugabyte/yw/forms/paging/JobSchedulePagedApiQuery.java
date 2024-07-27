// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.forms.paging;

import com.yugabyte.yw.forms.filters.JobScheduleApiFilter;
import com.yugabyte.yw.models.JobSchedule;
import com.yugabyte.yw.models.paging.PagedQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class JobSchedulePagedApiQuery
    extends PagedQuery<JobScheduleApiFilter, JobSchedule.SortBy> {}
