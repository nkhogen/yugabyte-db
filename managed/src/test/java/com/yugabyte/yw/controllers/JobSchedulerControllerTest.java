// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.controllers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static play.mvc.Http.Status.OK;
import static play.test.Helpers.contentAsString;

import com.fasterxml.jackson.databind.JsonNode;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.forms.filters.JobScheduleApiFilter;
import com.yugabyte.yw.forms.paging.JobSchedulePagedApiQuery;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.JobSchedule;
import com.yugabyte.yw.models.Users;
import com.yugabyte.yw.models.paging.AlertPagedResponse;
import com.yugabyte.yw.models.paging.PagedQuery;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import play.libs.Json;
import play.mvc.Result;

@RunWith(MockitoJUnitRunner.class)
public class JobSchedulerControllerTest extends FakeDBApplication {
  private Customer customer;
  private Users user;
  private String authToken;

  @Before
  public void setUp() {
    customer = ModelFactory.testCustomer();
    user = ModelFactory.testUser(customer);
    authToken = user.createAuthToken();
  }

  public void testPageListJobSchedules() {
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
    JsonNode alertsJson = Json.parse(contentAsString(result));
    AlertPagedResponse alerts = Json.fromJson(alertsJson, AlertPagedResponse.class);

    assertThat(alerts.isHasNext(), is(false));
    assertThat(alerts.isHasPrev(), is(true));
    assertThat(alerts.getTotalCount(), equalTo(3));
    assertThat(alerts.getEntities(), hasSize(2));
  }
}
