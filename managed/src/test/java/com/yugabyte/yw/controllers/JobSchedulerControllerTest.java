// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.controllers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static play.mvc.Http.Status.OK;
import static play.test.Helpers.contentAsString;

import com.fasterxml.jackson.databind.JsonNode;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.forms.JobScheduleSnoozeForm;
import com.yugabyte.yw.forms.JobScheduleUpdateForm;
import com.yugabyte.yw.forms.filters.JobInstanceApiFilter;
import com.yugabyte.yw.forms.filters.JobScheduleApiFilter;
import com.yugabyte.yw.forms.paging.JobInstancePagedApiQuery;
import com.yugabyte.yw.forms.paging.JobInstancePagedApiResponse;
import com.yugabyte.yw.forms.paging.JobSchedulePagedApiQuery;
import com.yugabyte.yw.forms.paging.JobSchedulePagedApiResponse;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.JobInstance;
import com.yugabyte.yw.models.JobSchedule;
import com.yugabyte.yw.models.Users;
import com.yugabyte.yw.models.helpers.schedule.JobConfig;
import com.yugabyte.yw.models.helpers.schedule.ScheduleConfig;
import com.yugabyte.yw.models.helpers.schedule.ScheduleConfig.ScheduleType;
import com.yugabyte.yw.models.paging.PagedQuery;
import com.yugabyte.yw.scheduler.JobScheduler;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import play.libs.Json;
import play.mvc.Result;

@RunWith(MockitoJUnitRunner.class)
public class JobSchedulerControllerTest extends FakeDBApplication {
  private JobScheduler jobScheduler;
  private Customer customer;
  private Users user;
  private String authToken;

  @SuppressWarnings("serial")
  @Getter
  @Builder
  @Jacksonized
  public static class DummyJobConfig implements JobConfig {
    private String field;

    @Override
    public CompletableFuture<?> executeJob(RuntimeParams runtimeParams) {
      return CompletableFuture.completedFuture(null);
    }
  }

  @Before
  public void setUp() {
    jobScheduler = app.injector().instanceOf(JobScheduler.class);
    customer = ModelFactory.testCustomer();
    user = ModelFactory.testUser(customer);
    authToken = user.createAuthToken();
  }

  @After
  public void tearDown() {
    JobSchedule.getAll().forEach(j -> j.delete());
  }

  private JobSchedule createJobSchedule(String name, Duration interval, boolean disabled) {
    JobSchedule jobSchedule = new JobSchedule();
    jobSchedule.setCustomerUuid(customer.getUuid());
    jobSchedule.setName(name);
    jobSchedule.setScheduleConfig(
        ScheduleConfig.builder().disabled(disabled).interval(interval).build());
    jobSchedule.setJobConfig(DummyJobConfig.builder().field(name).build());
    return JobSchedule.getOrBadRequest(jobScheduler.submitSchedule(jobSchedule));
  }

  private JobInstance createJobInstance(UUID jobScheduleUuid) {
    JobSchedule jobSchedule = JobSchedule.getOrBadRequest(jobScheduleUuid);
    JobInstance jobInstance = new JobInstance();
    jobInstance.setJobScheduleUuid(jobSchedule.getUuid());
    jobInstance.setStartTime(jobSchedule.getNextStartTime());
    jobInstance.insert();
    return jobInstance;
  }

  private List<JobSchedule> createJobSchedules(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> createJobSchedule("test_" + i, Duration.ofMinutes(3), false))
        .collect(Collectors.toList());
  }

  @Test
  public void testPageListJobSchedules() {
    createJobSchedules(3);
    JobScheduleApiFilter filter = new JobScheduleApiFilter();
    JobSchedulePagedApiQuery query = new JobSchedulePagedApiQuery();
    query.setSortBy(JobSchedule.SortBy.name);
    query.setDirection(PagedQuery.SortDirection.DESC);
    query.setLimit(2);
    query.setOffset(1);
    query.setFilter(filter);
    query.setNeedTotalCount(true);
    Result result =
        doRequestWithAuthTokenAndBody(
            "POST",
            "/api/customers/" + customer.getUuid() + "/job_schedules/page",
            authToken,
            Json.toJson(query));
    assertThat(result.status(), equalTo(OK));
    JsonNode response = Json.parse(contentAsString(result));
    JobSchedulePagedApiResponse jobSchedules =
        Json.fromJson(response, JobSchedulePagedApiResponse.class);
    assertThat(jobSchedules.isHasNext(), is(false));
    assertThat(jobSchedules.isHasPrev(), is(true));
    assertThat(jobSchedules.getTotalCount(), equalTo(3));
    assertThat(jobSchedules.getEntities(), hasSize(2));
  }

  @Test
  public void testGetJobSchedule() {
    List<JobSchedule> jobSchedules = createJobSchedules(3);
    Result result =
        doRequestWithAuthToken(
            "GET",
            "/api/customers/"
                + customer.getUuid()
                + "/job_schedules/"
                + jobSchedules.get(0).getUuid(),
            authToken);
    assertThat(result.status(), equalTo(OK));
    JsonNode response = Json.parse(contentAsString(result));
    JobSchedule jobSchedule = Json.fromJson(response, JobSchedule.class);
    assertEquals(jobSchedules.get(0).getUuid(), jobSchedule.getUuid());
    assertEquals(jobSchedules.get(0).getName(), jobSchedule.getName());
    assertEquals(
        jobSchedules.get(0).getScheduleConfig().getType(),
        jobSchedule.getScheduleConfig().getType());
    assertEquals(
        jobSchedules.get(0).getScheduleConfig().getInterval(),
        jobSchedule.getScheduleConfig().getInterval());
    assertEquals(
        jobSchedules.get(0).getScheduleConfig().isDisabled(),
        jobSchedule.getScheduleConfig().isDisabled());
    assertEquals(
        jobSchedules.get(0).getJobConfig().getClass(), jobSchedule.getJobConfig().getClass());
  }

  @Test
  public void testUpdateJobSchedule() {
    List<JobSchedule> jobSchedules = createJobSchedules(2);
    JobSchedule jobSchedule = jobSchedules.get(0);
    JobScheduleUpdateForm updateForm = new JobScheduleUpdateForm();
    // Non-default type.
    updateForm.type = ScheduleType.FIXED_RATE;
    updateForm.interval = Duration.ofMinutes(10);
    Result result =
        doRequestWithAuthTokenAndBody(
            "PUT",
            "/api/customers/"
                + customer.getUuid()
                + "/job_schedules/"
                + jobSchedules.get(0).getUuid(),
            authToken,
            Json.toJson(updateForm));
    assertThat(result.status(), equalTo(OK));
    JsonNode response = Json.parse(contentAsString(result));
    JobSchedule updatedJobSchedule = Json.fromJson(response, JobSchedule.class);
    assertEquals(jobSchedules.get(0).getUuid(), jobSchedule.getUuid());
    assertEquals(Duration.ofMinutes(10), updatedJobSchedule.getScheduleConfig().getInterval());
    // Verify in DB as well.
    updatedJobSchedule = JobSchedule.getOrBadRequest(jobSchedule.getUuid());
    assertEquals(jobSchedules.get(0).getUuid(), jobSchedule.getUuid());
    assertEquals(ScheduleType.FIXED_RATE, updatedJobSchedule.getScheduleConfig().getType());
    assertEquals(Duration.ofMinutes(10), updatedJobSchedule.getScheduleConfig().getInterval());
    // Other one must not get updated.
    JobSchedule otherJobSchedule = JobSchedule.getOrBadRequest(jobSchedules.get(1).getUuid());
    assertEquals(ScheduleType.FIXED_DELAY, otherJobSchedule.getScheduleConfig().getType());
    assertEquals(Duration.ofMinutes(3), otherJobSchedule.getScheduleConfig().getInterval());
  }

  @Test
  public void testSnoozeJobSchedule() {
    List<JobSchedule> jobSchedules = createJobSchedules(2);
    JobSchedule jobSchedule = jobSchedules.get(0);
    JobScheduleSnoozeForm snoozeForm = new JobScheduleSnoozeForm();
    snoozeForm.snoozeSecs = 600;
    Result result =
        doRequestWithAuthTokenAndBody(
            "PUT",
            "/api/customers/"
                + customer.getUuid()
                + "/job_schedules/"
                + jobSchedules.get(0).getUuid()
                + "/snooze",
            authToken,
            Json.toJson(snoozeForm));
    assertThat(result.status(), equalTo(OK));
    JsonNode response = Json.parse(contentAsString(result));
    JobSchedule updatedJobSchedule = Json.fromJson(response, JobSchedule.class);
    assertEquals(jobSchedules.get(0).getUuid(), jobSchedule.getUuid());
    assertEquals(Duration.ofMinutes(3), updatedJobSchedule.getScheduleConfig().getInterval());
    assertTrue(
        updatedJobSchedule
            .getNextStartTime()
            .after(Date.from(Instant.now().plus(10L, ChronoUnit.MINUTES))));
    // Verify in DB as well.
    updatedJobSchedule = JobSchedule.getOrBadRequest(jobSchedule.getUuid());
    assertEquals(jobSchedules.get(0).getUuid(), jobSchedule.getUuid());
    assertEquals(Duration.ofMinutes(3), updatedJobSchedule.getScheduleConfig().getInterval());
    assertTrue(
        updatedJobSchedule
            .getNextStartTime()
            .after(Date.from(Instant.now().plus(10L, ChronoUnit.MINUTES))));
    // Other one must not get updated.
    JobSchedule otherJobSchedule = JobSchedule.getOrBadRequest(jobSchedules.get(1).getUuid());
    assertEquals(Duration.ofMinutes(3), otherJobSchedule.getScheduleConfig().getInterval());
    assertTrue(
        otherJobSchedule
            .getNextStartTime()
            .before(Date.from(Instant.now().plus(10L, ChronoUnit.MINUTES))));
  }

  @Test
  public void testPageListJobInstances() {
    JobSchedule jobSchedule = createJobSchedules(1).get(0);
    createJobInstance(jobSchedule.getUuid());
    createJobInstance(jobSchedule.getUuid());
    createJobInstance(jobSchedule.getUuid());
    JobInstanceApiFilter filter = new JobInstanceApiFilter();
    JobInstancePagedApiQuery query = new JobInstancePagedApiQuery();
    query.setSortBy(JobInstance.SortBy.jobScheduleUuid);
    query.setDirection(PagedQuery.SortDirection.DESC);
    query.setLimit(2);
    query.setOffset(1);
    query.setFilter(filter);
    query.setNeedTotalCount(true);
    Result result =
        doRequestWithAuthTokenAndBody(
            "POST",
            "/api/customers/"
                + customer.getUuid()
                + "/job_schedules/"
                + jobSchedule.getUuid()
                + "/job_instances/page",
            authToken,
            Json.toJson(query));
    assertThat(result.status(), equalTo(OK));
    JsonNode response = Json.parse(contentAsString(result));
    JobInstancePagedApiResponse jobInstances =
        Json.fromJson(response, JobInstancePagedApiResponse.class);
    assertThat(jobInstances.isHasNext(), is(false));
    assertThat(jobInstances.isHasPrev(), is(true));
    assertThat(jobInstances.getTotalCount(), equalTo(3));
    assertThat(jobInstances.getEntities(), hasSize(2));
  }
}
