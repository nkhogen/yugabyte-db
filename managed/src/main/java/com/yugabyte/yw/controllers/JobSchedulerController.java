// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.controllers;

import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.common.rbac.PermissionInfo.Action;
import com.yugabyte.yw.common.rbac.PermissionInfo.ResourceType;
import com.yugabyte.yw.controllers.handlers.JobSchedulerHandler;
import com.yugabyte.yw.forms.JobScheduleResp;
import com.yugabyte.yw.forms.JobScheduleUpdateForm;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.forms.PlatformResults.YBPSuccess;
import com.yugabyte.yw.forms.paging.JobInstancePagedApiQuery;
import com.yugabyte.yw.forms.paging.JobInstancePagedApiResponse;
import com.yugabyte.yw.forms.paging.JobSchedulePagedApiQuery;
import com.yugabyte.yw.forms.paging.JobSchedulePagedApiResponse;
import com.yugabyte.yw.models.Audit;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.common.YbaApi;
import com.yugabyte.yw.models.paging.JobInstancePagedQuery;
import com.yugabyte.yw.models.paging.JobSchedulePagedQuery;
import com.yugabyte.yw.rbac.annotations.AuthzPath;
import com.yugabyte.yw.rbac.annotations.PermissionAttribute;
import com.yugabyte.yw.rbac.annotations.RequiredPermissionOnResource;
import com.yugabyte.yw.rbac.annotations.Resource;
import com.yugabyte.yw.rbac.enums.SourceType;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.util.UUID;
import javax.inject.Inject;
import play.mvc.Http;
import play.mvc.Result;

@Api(
    value = "Job Scheduler",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
public class JobSchedulerController extends AuthenticatedController {
  @Inject JobSchedulerHandler jobSchedulerHandler;

  @ApiOperation(
      value = "List Job Schedules (paginated)",
      response = JobSchedulePagedApiResponse.class,
      nickname = "PageListJobSchedules")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "PageJobScheduleRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.paging.JobSchedulePagedApiQuery",
          required = true))
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(resourceType = ResourceType.OTHER, action = Action.READ),
        resourceLocation = @Resource(path = Util.CUSTOMERS, sourceType = SourceType.ENDPOINT))
  })
  @YbaApi(visibility = YbaApi.YbaApiVisibility.PREVIEW, sinceYBAVersion = "2024.2.1.0")
  public Result pageJobSchedules(UUID customerUuid, Http.Request request) {
    Customer.getOrBadRequest(customerUuid);
    JobSchedulePagedApiQuery apiQuery =
        parseJsonAndValidate(request, JobSchedulePagedApiQuery.class);
    JobSchedulePagedQuery query =
        apiQuery.copyWithFilter(apiQuery.getFilter().toFilter(), JobSchedulePagedQuery.class);
    JobSchedulePagedApiResponse response =
        jobSchedulerHandler.pagedListJobSchedules(customerUuid, query);
    return PlatformResults.withData(response);
  }

  @ApiOperation(
      value = "Get Job Schedule",
      response = JobScheduleResp.class,
      nickname = "GetJobSchedule")
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(resourceType = ResourceType.OTHER, action = Action.READ),
        resourceLocation = @Resource(path = Util.CUSTOMERS, sourceType = SourceType.ENDPOINT))
  })
  @YbaApi(visibility = YbaApi.YbaApiVisibility.PREVIEW, sinceYBAVersion = "2024.2.1.0")
  public Result getJobSchedule(UUID customerUuid, UUID jobScheduleUuid) {
    return PlatformResults.withData(
        jobSchedulerHandler.getJobSchedule(customerUuid, jobScheduleUuid));
  }

  @ApiOperation(
      value = "Update Job Schedule",
      response = JobScheduleResp.class,
      hidden = true,
      nickname = "UpdateJobSchedule")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "NodeAgentForm",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.NodeAgentForm",
          required = true))
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(resourceType = ResourceType.OTHER, action = Action.UPDATE),
        resourceLocation = @Resource(path = Util.CUSTOMERS, sourceType = SourceType.ENDPOINT))
  })
  @YbaApi(visibility = YbaApi.YbaApiVisibility.PREVIEW, sinceYBAVersion = "2024.2.1.0")
  public Result updateJobSchedule(UUID customerUuid, UUID jobScheduleUuid, Http.Request request) {
    JobScheduleUpdateForm payload = parseJsonAndValidate(request, JobScheduleUpdateForm.class);
    JobScheduleResp response =
        jobSchedulerHandler.updateJobSchedule(customerUuid, jobScheduleUuid, payload);
    auditService()
        .createAuditEntry(
            request,
            Audit.TargetType.JobSchedule,
            jobScheduleUuid.toString(),
            Audit.ActionType.Update);
    return PlatformResults.withData(response);
  }

  @ApiOperation(
      value = "Delete Job Schedule",
      response = YBPSuccess.class,
      hidden = true,
      nickname = "DeleteJobSchedule")
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(resourceType = ResourceType.OTHER, action = Action.UPDATE),
        resourceLocation = @Resource(path = Util.CUSTOMERS, sourceType = SourceType.ENDPOINT))
  })
  @YbaApi(visibility = YbaApi.YbaApiVisibility.PREVIEW, sinceYBAVersion = "2024.2.1.0")
  public Result deleteJobSchedule(UUID customerUuid, UUID jobScheduleUuid, Http.Request request) {
    jobSchedulerHandler.deleteJobSchedule(customerUuid, jobScheduleUuid);
    auditService()
        .createAuditEntry(
            request,
            Audit.TargetType.JobSchedule,
            jobScheduleUuid.toString(),
            Audit.ActionType.Delete);
    return YBPSuccess.empty();
  }

  @ApiOperation(
      value = "List Job Instances (paginated)",
      response = JobInstancePagedApiResponse.class,
      nickname = "PageListJobInstances")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "PageJobInstanceRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.paging.JobInstancePagedApiQuery",
          required = true))
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(resourceType = ResourceType.OTHER, action = Action.READ),
        resourceLocation = @Resource(path = Util.CUSTOMERS, sourceType = SourceType.ENDPOINT))
  })
  @YbaApi(visibility = YbaApi.YbaApiVisibility.PREVIEW, sinceYBAVersion = "2024.2.1.0")
  public Result pageJobInstances(UUID customerUuid, UUID jobScheduleUuid, Http.Request request) {
    Customer.getOrBadRequest(customerUuid);
    JobInstancePagedApiQuery apiQuery =
        parseJsonAndValidate(request, JobInstancePagedApiQuery.class);
    JobInstancePagedQuery query =
        apiQuery.copyWithFilter(apiQuery.getFilter().toFilter(), JobInstancePagedQuery.class);
    JobInstancePagedApiResponse response =
        jobSchedulerHandler.pagedListJobInstances(customerUuid, jobScheduleUuid, query);
    return PlatformResults.withData(response);
  }
}
