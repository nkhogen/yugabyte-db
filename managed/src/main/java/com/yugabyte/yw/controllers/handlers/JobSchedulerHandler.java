// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.controllers.handlers;

import static com.yugabyte.yw.models.helpers.CommonUtils.performPagedQuery;

import com.yugabyte.yw.forms.JobScheduleResp;
import com.yugabyte.yw.forms.JobScheduleSnoozeForm;
import com.yugabyte.yw.forms.JobScheduleUpdateForm;
import com.yugabyte.yw.forms.paging.JobInstancePagedApiResponse;
import com.yugabyte.yw.forms.paging.JobSchedulePagedApiResponse;
import com.yugabyte.yw.models.JobInstance;
import com.yugabyte.yw.models.JobSchedule;
import com.yugabyte.yw.models.helpers.schedule.ScheduleConfig;
import com.yugabyte.yw.models.paging.JobInstancePagedQuery;
import com.yugabyte.yw.models.paging.JobInstancePagedResponse;
import com.yugabyte.yw.models.paging.JobSchedulePagedQuery;
import com.yugabyte.yw.models.paging.JobSchedulePagedResponse;
import com.yugabyte.yw.models.paging.PagedQuery.SortDirection;
import com.yugabyte.yw.scheduler.JobScheduler;
import io.ebean.Query;
import java.time.Duration;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class JobSchedulerHandler {
  private JobScheduler jobScheduler;

  @Inject
  public JobSchedulerHandler(JobScheduler jobScheduler) {
    this.jobScheduler = jobScheduler;
  }

  public JobSchedulePagedApiResponse pagedListJobSchedules(
      UUID customerUuid, JobSchedulePagedQuery pagedQuery) {
    if (pagedQuery.getSortBy() == null) {
      pagedQuery.setSortBy(JobSchedule.SortBy.name);
      pagedQuery.setDirection(SortDirection.DESC);
    }
    Query<JobSchedule> query =
        JobSchedule.createQuery(customerUuid, pagedQuery.getFilter()).query();
    JobSchedulePagedResponse response =
        performPagedQuery(query, pagedQuery, JobSchedulePagedResponse.class);
    return response.convertToApiResponse();
  }

  public JobScheduleResp getJobSchedule(UUID customerUuid, UUID jobScheduleUuid) {
    return new JobScheduleResp(JobSchedule.getOrBadRequest(customerUuid, jobScheduleUuid));
  }

  public JobScheduleResp updateJobSchedule(
      UUID customerUuid, UUID jobScheduleUuid, JobScheduleUpdateForm form) {
    ScheduleConfig.ScheduleConfigBuilder builder =
        JobSchedule.getOrBadRequest(customerUuid, jobScheduleUuid).getScheduleConfig().toBuilder();
    builder.interval(form.interval);
    builder.disabled(form.disable);
    builder.type(form.type);
    return new JobScheduleResp(jobScheduler.updateSchedule(jobScheduleUuid, builder.build()));
  }

  public JobScheduleResp snoozeJobSchedule(
      UUID customerUuid, UUID jobScheduleUuid, JobScheduleSnoozeForm form) {
    JobSchedule jobSchedule = JobSchedule.getOrBadRequest(customerUuid, jobScheduleUuid);
    return new JobScheduleResp(
        jobScheduler.snooze(jobSchedule.getUuid(), Duration.ofSeconds(form.snoozeSecs)));
  }

  public void deleteJobSchedule(UUID customerUuid, UUID jobScheduleUuid) {
    JobSchedule.maybeGet(customerUuid, jobScheduleUuid)
        .ifPresent(j -> jobScheduler.deleteSchedule(jobScheduleUuid));
  }

  public JobInstancePagedApiResponse pagedListJobInstances(
      UUID customerUuid, UUID jobScheduleUuid, JobInstancePagedQuery pagedQuery) {
    JobSchedule.getOrBadRequest(customerUuid, jobScheduleUuid);
    if (pagedQuery.getSortBy() == null) {
      pagedQuery.setSortBy(JobInstance.SortBy.jobScheduleUuid);
      pagedQuery.setDirection(SortDirection.DESC);
    }
    Query<JobInstance> query =
        JobInstance.createQuery(jobScheduleUuid, pagedQuery.getFilter()).query();
    JobInstancePagedResponse response =
        performPagedQuery(query, pagedQuery, JobInstancePagedResponse.class);
    return response.convertToApiResponse();
  }
}
