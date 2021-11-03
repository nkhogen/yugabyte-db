package com.yugabyte.yw.commissioner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static play.inject.Bindings.bind;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import com.yugabyte.yw.common.PlatformGuiceApplicationBaseTest;
import com.yugabyte.yw.common.ha.PlatformReplicationManager;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.helpers.TaskType;

import kamon.instrumentation.play.GuiceModule;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.modules.swagger.SwaggerModule;
import play.test.Helpers;

@RunWith(MockitoJUnitRunner.class)
public class TaskExecutorTest extends PlatformGuiceApplicationBaseTest {

  private TaskExecutor taskExecutor;
  private PlatformReplicationManager platformReplicationManager;

  @Override
  protected Application provideApplication() {
    platformReplicationManager = mock(PlatformReplicationManager.class);

    return configureApplication(
            new GuiceApplicationBuilder()
                .disable(SwaggerModule.class)
                .disable(GuiceModule.class)
                .configure((Map) Helpers.inMemoryDatabase())
                .overrides(
                    bind(PlatformReplicationManager.class).toInstance(platformReplicationManager))
                .overrides(
                    bind(ExecutorServiceProvider.class).to(DefaultExecutorServiceProvider.class)))
        .build();
  }

  @Before
  public void setup() {
    taskExecutor = app.injector().instanceOf(TaskExecutor.class);
  }

  @Test
  public void testTaskSubmission() throws InterruptedException {
    UUID taskUUID = UUID.randomUUID();
    ITask task = mock(ITask.class);
    TaskInfo taskInfo = mock(TaskInfo.class);
    when(taskInfo.getTaskUUID()).thenReturn(taskUUID);
    when(taskInfo.getTaskType()).thenReturn(TaskType.BackupTable);
    when(taskInfo.getCreationTime()).thenReturn(new Date());
    doAnswer(
            ans -> {
              return null;
            })
        .when(taskInfo)
        .setTaskState(any());
    doAnswer(
            inv -> {
              return null;
            })
        .when(taskInfo)
        .save();
    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              latch.countDown();
              return null;
            })
        .when(task)
        .run();
    TaskExecutor.TaskRunner taskRunner = taskExecutor.new TaskRunner(task, taskInfo);
    UUID outTaskUUID = taskExecutor.submit(taskRunner);
    assertEquals(taskUUID, outTaskUUID);
    if (!latch.await(3, TimeUnit.SECONDS)) {
      fail();
    }
  }
}
