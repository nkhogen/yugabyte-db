// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.forms.paging;

import com.yugabyte.yw.forms.filters.JobInstanceApiFilter;
import com.yugabyte.yw.models.JobInstance;
import com.yugabyte.yw.models.paging.PagedQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class JobInstancePagedApiQuery
    extends PagedQuery<JobInstanceApiFilter, JobInstance.SortBy> {}
