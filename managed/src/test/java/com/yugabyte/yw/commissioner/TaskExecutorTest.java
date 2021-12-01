// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static play.inject.Bindings.bind;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yugabyte.yw.commissioner.TaskExecutor.TaskExecutionListener;
import com.yugabyte.yw.common.PlatformGuiceApplicationBaseTest;
import com.yugabyte.yw.common.ha.PlatformReplicationManager;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.helpers.TaskType;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kamon.instrumentation.play.GuiceModule;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.MockitoJUnitRunner;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.modules.swagger.SwaggerModule;
import play.test.Helpers;

@RunWith(MockitoJUnitRunner.class)
public class TaskExecutorTest extends PlatformGuiceApplicationBaseTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private TaskExecutor taskExecutor;

  @Override
  protected Application provideApplication() {
    return configureApplication(
            new GuiceApplicationBuilder()
                .disable(SwaggerModule.class)
                .disable(GuiceModule.class)
                .configure((Map) Helpers.inMemoryDatabase())
                .overrides(
                    bind(PlatformReplicationManager.class)
                        .toInstance(mock(PlatformReplicationManager.class)))
                .overrides(
                    bind(ExecutorServiceProvider.class).to(DefaultExecutorServiceProvider.class)))
        .build();
  }

  private void waitForTask(UUID taskUUID) {
    long elapsedTimeMs = 0;
    while (taskExecutor.isTaskRunning(taskUUID) && elapsedTimeMs < 5000) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
      }
      elapsedTimeMs += 100;
    }
    if (taskExecutor.isTaskRunning(taskUUID)) {
      fail("Task " + taskUUID + " did not complete in time");
    }
  }

  private ITask mockTaskCommon(UUID taskUUID) {
    ITask task = mock(ITask.class);
    when(task.getName()).thenReturn("TestTask-" + taskUUID);
    return task;
  }

  private TaskInfo mockTaskInfoCommon(
      TaskType taskType, UUID taskUUID, UUID parentTaskUUID, ObjectNode taskDetailsOut) {
    TaskInfo taskInfo = mock(TaskInfo.class);
    when(taskInfo.getTaskUUID()).thenReturn(taskUUID);
    when(taskInfo.getTaskType()).thenReturn(taskType);
    when(taskInfo.getCreationTime()).thenReturn(new Date());
    when(taskInfo.getParentUUID()).thenReturn(parentTaskUUID);
    if (taskDetailsOut != null) {
      when(taskInfo.getTaskDetails()).thenReturn(taskDetailsOut);
      doAnswer(
              inv -> {
                Object[] args = inv.getArguments();
                ObjectNode node = (ObjectNode) args[0];
                taskDetailsOut.set("errorString", node.get("errorString"));
                return null;
              })
          .when(taskInfo)
          .setTaskDetails(any());
    }
    return taskInfo;
  }

  @Before
  public void setup() {
    taskExecutor = spy(app.injector().instanceOf(TaskExecutor.class));
  }

  @Test
  public void testTaskSubmission() throws InterruptedException {
    UUID taskUUID = UUID.randomUUID();
    ITask task = mockTaskCommon(taskUUID);
    TaskInfo taskInfo = mockTaskInfoCommon(TaskType.BackupTable, taskUUID, null, null);
    TaskExecutor.TaskRunner taskRunner = taskExecutor.new TaskRunner(task, taskInfo);
    UUID outTaskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));
    assertEquals(taskUUID, outTaskUUID);
    waitForTask(outTaskUUID);
    verify(task, times(1)).run();
    ArgumentCaptor<TaskInfo.State> arg = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(taskInfo, times(2)).setTaskState(arg.capture());
    List<TaskInfo.State> taskStates = arg.getAllValues();
    assertEquals(TaskInfo.State.Running, taskStates.get(0));
    assertEquals(TaskInfo.State.Success, taskStates.get(1));
  }

  @Test
  public void testTaskFailure() throws InterruptedException {
    UUID taskUUID = UUID.randomUUID();
    ObjectNode objectNode = mapper.createObjectNode();
    ITask task = mockTaskCommon(taskUUID);
    TaskInfo taskInfo = mockTaskInfoCommon(TaskType.BackupTable, taskUUID, null, objectNode);

    doThrow(new RuntimeException("Error occurred in task")).when(task).run();

    TaskExecutor.TaskRunner taskRunner = taskExecutor.new TaskRunner(task, taskInfo);
    UUID outTaskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));
    assertEquals(taskUUID, outTaskUUID);
    waitForTask(outTaskUUID);

    verify(taskInfo, atLeast(1)).getTaskDetails();
    verify(taskInfo, times(1)).setTaskDetails(any());

    ArgumentCaptor<TaskInfo.State> arg = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(taskInfo, times(2)).setTaskState(arg.capture());
    List<TaskInfo.State> taskStates = arg.getAllValues();

    assertEquals(TaskInfo.State.Running, taskStates.get(0));
    assertEquals(TaskInfo.State.Failure, taskStates.get(1));

    String errMsg = objectNode.get("errorString").asText();
    assertTrue("Found " + errMsg, errMsg.contains("Error occurred in task"));
  }

  @Test
  public void testSubTaskSyncSuccess() throws InterruptedException {
    UUID taskUUID = UUID.randomUUID();
    UUID subTaskUUID = UUID.randomUUID();

    ITask task = mockTaskCommon(taskUUID);
    ITask subTask = mockTaskCommon(subTaskUUID);

    TaskInfo taskInfo = mockTaskInfoCommon(TaskType.BackupTable, taskUUID, null, null);
    TaskInfo subTaskInfo = mockTaskInfoCommon(TaskType.BackupTable, subTaskUUID, taskUUID, null);

    doReturn(subTaskInfo).when(taskExecutor).createTaskInfo(any());

    doAnswer(
            inv -> {
              // Invoke subTask from the parent task.
              taskExecutor.runSyncSubTask(subTask);
              return null;
            })
        .when(task)
        .run();

    TaskExecutor.TaskRunner taskRunner = taskExecutor.new TaskRunner(task, taskInfo);
    UUID outTaskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));

    assertEquals(taskUUID, outTaskUUID);
    waitForTask(outTaskUUID);
    verify(subTask, times(1)).run();

    ArgumentCaptor<TaskInfo.State> taskArg = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(taskInfo, times(2)).setTaskState(taskArg.capture());
    List<TaskInfo.State> taskStates = taskArg.getAllValues();

    assertEquals(TaskInfo.State.Running, taskStates.get(0));
    assertEquals(TaskInfo.State.Success, taskStates.get(1));

    ArgumentCaptor<TaskInfo.State> subTaskArg = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(subTaskInfo, times(2)).setTaskState(subTaskArg.capture());
    List<TaskInfo.State> subTaskStates = subTaskArg.getAllValues();

    assertEquals(TaskInfo.State.Running, subTaskStates.get(0));
    assertEquals(TaskInfo.State.Success, subTaskStates.get(1));
  }

  @Test
  public void testSubTaskSyncFailure() throws InterruptedException {
    UUID taskUUID = UUID.randomUUID();
    UUID subTaskUUID = UUID.randomUUID();
    ObjectNode objectNodeTask = mapper.createObjectNode();
    ObjectNode objectNodeSubTask = mapper.createObjectNode();

    ITask task = mockTaskCommon(taskUUID);
    ITask subTask = mockTaskCommon(subTaskUUID);

    TaskInfo taskInfo = mockTaskInfoCommon(TaskType.BackupTable, taskUUID, null, objectNodeTask);
    TaskInfo subTaskInfo =
        mockTaskInfoCommon(TaskType.BackupTable, subTaskUUID, taskUUID, objectNodeSubTask);

    doReturn(subTaskInfo).when(taskExecutor).createTaskInfo(any());

    doAnswer(
            inv -> {
              // Invoke subTask from the parent task.
              taskExecutor.runSyncSubTask(subTask);
              return null;
            })
        .when(task)
        .run();

    doThrow(new RuntimeException("Error occurred in subtask")).when(subTask).run();

    TaskExecutor.TaskRunner taskRunner = taskExecutor.new TaskRunner(task, taskInfo);
    UUID outTaskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));

    assertEquals(taskUUID, outTaskUUID);
    waitForTask(outTaskUUID);

    ArgumentCaptor<TaskInfo.State> taskArg = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(taskInfo, times(2)).setTaskState(taskArg.capture());
    List<TaskInfo.State> taskStates = taskArg.getAllValues();

    assertEquals(TaskInfo.State.Running, taskStates.get(0));
    assertEquals(TaskInfo.State.Failure, taskStates.get(1));

    verify(taskInfo, atLeast(1)).getTaskDetails();
    verify(taskInfo, times(1)).setTaskDetails(any());

    verify(subTaskInfo, atLeast(1)).getTaskDetails();
    verify(subTaskInfo, times(1)).setTaskDetails(any());

    String errMsg = objectNodeTask.get("errorString").asText();
    assertTrue("Found " + errMsg, errMsg.contains("Error occurred in subtask"));

    ArgumentCaptor<TaskInfo.State> subTaskArg = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(subTaskInfo, times(2)).setTaskState(subTaskArg.capture());
    List<TaskInfo.State> subTaskStates = subTaskArg.getAllValues();

    assertEquals(TaskInfo.State.Running, subTaskStates.get(0));
    assertEquals(TaskInfo.State.Failure, subTaskStates.get(1));

    errMsg = objectNodeSubTask.get("errorString").asText();
    assertTrue("Found " + errMsg, errMsg.contains("Error occurred in subtask"));
  }

  @Test
  public void testSubTaskAsyncSuccess() throws InterruptedException {
    UUID taskUUID = UUID.randomUUID();
    UUID subTaskUUID = UUID.randomUUID();

    ITask task = mockTaskCommon(taskUUID);
    ITask subTask = mockTaskCommon(subTaskUUID);

    TaskInfo taskInfo = mockTaskInfoCommon(TaskType.BackupTable, taskUUID, null, null);
    TaskInfo subTaskInfo = mockTaskInfoCommon(TaskType.BackupTable, subTaskUUID, taskUUID, null);

    doReturn(subTaskInfo).when(taskExecutor).createTaskInfo(any());

    doAnswer(
            inv -> {
              // Invoke subTask from the parent task.
              taskExecutor.addAsyncSubTask(subTask);
              taskExecutor.runAsyncSubTasks();
              return null;
            })
        .when(task)
        .run();

    TaskExecutor.TaskRunner taskRunner = taskExecutor.new TaskRunner(task, taskInfo);
    UUID outTaskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));

    assertEquals(taskUUID, outTaskUUID);
    waitForTask(outTaskUUID);
    verify(subTask, times(1)).run();

    ArgumentCaptor<TaskInfo.State> taskArg = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(taskInfo, times(2)).setTaskState(taskArg.capture());
    List<TaskInfo.State> taskStates = taskArg.getAllValues();

    assertEquals(TaskInfo.State.Running, taskStates.get(0));
    assertEquals(TaskInfo.State.Success, taskStates.get(1));

    ArgumentCaptor<TaskInfo.State> subTaskArg = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(subTaskInfo, times(2)).setTaskState(subTaskArg.capture());
    List<TaskInfo.State> subTaskStates = subTaskArg.getAllValues();

    assertEquals(TaskInfo.State.Running, subTaskStates.get(0));
    assertEquals(TaskInfo.State.Success, subTaskStates.get(1));
  }

  @Test
  public void testSubTaskAsyncFailure() throws InterruptedException {
    UUID taskUUID = UUID.randomUUID();
    UUID subTaskUUID = UUID.randomUUID();
    ObjectNode objectNodeTask = mapper.createObjectNode();
    ObjectNode objectNodeSubTask = mapper.createObjectNode();

    ITask task = mockTaskCommon(taskUUID);
    ITask subTask = mockTaskCommon(subTaskUUID);

    TaskInfo taskInfo = mockTaskInfoCommon(TaskType.BackupTable, taskUUID, null, objectNodeTask);
    TaskInfo subTaskInfo =
        mockTaskInfoCommon(TaskType.BackupTable, subTaskUUID, taskUUID, objectNodeSubTask);

    doReturn(subTaskInfo).when(taskExecutor).createTaskInfo(any());

    doAnswer(
            inv -> {
              // Invoke subTask from the parent task.
              taskExecutor.addAsyncSubTask(subTask);
              taskExecutor.runAsyncSubTasks();
              return null;
            })
        .when(task)
        .run();

    doThrow(new RuntimeException("Error occurred in subtask")).when(subTask).run();

    TaskExecutor.TaskRunner taskRunner = taskExecutor.new TaskRunner(task, taskInfo);
    UUID outTaskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));

    assertEquals(taskUUID, outTaskUUID);
    waitForTask(outTaskUUID);

    ArgumentCaptor<TaskInfo.State> taskArg = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(taskInfo, times(2)).setTaskState(taskArg.capture());
    List<TaskInfo.State> taskStates = taskArg.getAllValues();

    assertEquals(TaskInfo.State.Running, taskStates.get(0));
    assertEquals(TaskInfo.State.Failure, taskStates.get(1));

    verify(taskInfo, atLeast(1)).getTaskDetails();
    verify(taskInfo, times(1)).setTaskDetails(any());

    verify(subTaskInfo, atLeast(1)).getTaskDetails();
    verify(subTaskInfo, times(1)).setTaskDetails(any());

    String errMsg = objectNodeTask.get("errorString").asText();
    assertTrue("Found " + errMsg, errMsg.contains("Error occurred in subtask"));

    ArgumentCaptor<TaskInfo.State> subTaskArg = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(subTaskInfo, times(2)).setTaskState(subTaskArg.capture());
    List<TaskInfo.State> subTaskStates = subTaskArg.getAllValues();

    assertEquals(TaskInfo.State.Running, subTaskStates.get(0));
    assertEquals(TaskInfo.State.Failure, subTaskStates.get(1));

    errMsg = objectNodeSubTask.get("errorString").asText();
    assertTrue("Found " + errMsg, errMsg.contains("Error occurred in subtask"));
  }

  @Test
  public void testSubTaskNonAbortable() throws InterruptedException {
    UUID taskUUID = UUID.randomUUID();
    ITask task = mockTaskCommon(taskUUID);
    TaskInfo taskInfo = mockTaskInfoCommon(TaskType.BackupTable, taskUUID, null, null);
    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              latch.await();
              return null;
            })
        .when(task)
        .run();

    TaskExecutor.TaskRunner taskRunner = taskExecutor.new TaskRunner(task, taskInfo);
    UUID outTaskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));
    assertEquals(taskUUID, outTaskUUID);
    try {
      assertThrows(RuntimeException.class, () -> taskExecutor.abort(taskUUID, false));
    } finally {
      latch.countDown();
    }
    waitForTask(outTaskUUID);
  }

  @Test
  public void testSubTaskAbort() throws InterruptedException {
    UUID taskUUID = UUID.randomUUID();
    UUID subTaskUUID1 = UUID.randomUUID();
    UUID subTaskUUID2 = UUID.randomUUID();

    ObjectNode objectNodeTask = mapper.createObjectNode();
    ObjectNode objectNodeSubTask2 = mapper.createObjectNode();

    ITask task = mockTaskCommon(taskUUID);
    ITask subTask1 = mockTaskCommon(subTaskUUID1);
    ITask subTask2 = mockTaskCommon(subTaskUUID2);

    TaskInfo taskInfo = mockTaskInfoCommon(TaskType.BackupTable, taskUUID, null, objectNodeTask);
    TaskInfo subTaskInfo1 = mockTaskInfoCommon(TaskType.BackupTable, subTaskUUID1, taskUUID, null);
    TaskInfo subTaskInfo2 =
        mockTaskInfoCommon(TaskType.BackupTable, subTaskUUID2, taskUUID, objectNodeSubTask2);

    doReturn(true).when(task).isAbortable();

    doReturn(subTaskInfo1, subTaskInfo2).when(taskExecutor).createTaskInfo(any());

    doAnswer(
            inv -> {
              // Invoke subTask from the parent task.
              taskExecutor.runSyncSubTask(subTask1);
              taskExecutor.runSyncSubTask(subTask2);
              return null;
            })
        .when(task)
        .run();

    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    doAnswer(
            inv -> {
              latch1.countDown();
              latch2.await();
              return null;
            })
        .when(subTask1)
        .run();

    TaskExecutor.TaskRunner taskRunner = taskExecutor.new TaskRunner(task, taskInfo);
    UUID outTaskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));
    assertEquals(taskUUID, outTaskUUID);

    if (!latch1.await(3, TimeUnit.SECONDS)) {
      fail();
    }
    // Stop the task
    taskExecutor.abort(taskUUID, false);
    latch2.countDown();

    waitForTask(outTaskUUID);

    verify(subTask1, times(1)).run();
    verify(subTask2, times(0)).run();

    ArgumentCaptor<TaskInfo.State> taskArg = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(taskInfo, times(2)).setTaskState(taskArg.capture());
    List<TaskInfo.State> taskStates = taskArg.getAllValues();

    assertEquals(TaskInfo.State.Running, taskStates.get(0));
    assertEquals(TaskInfo.State.Aborted, taskStates.get(1));

    ArgumentCaptor<TaskInfo.State> subTaskArg1 = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(subTaskInfo1, times(2)).setTaskState(subTaskArg1.capture());
    List<TaskInfo.State> subTaskStates1 = subTaskArg1.getAllValues();

    assertEquals(TaskInfo.State.Running, subTaskStates1.get(0));
    assertEquals(TaskInfo.State.Success, subTaskStates1.get(1));

    ArgumentCaptor<TaskInfo.State> subTaskArg2 = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(subTaskInfo2, atLeastOnce()).setTaskState(subTaskArg2.capture());
    List<TaskInfo.State> subTaskStates2 = subTaskArg2.getAllValues();
    assertEquals(TaskInfo.State.Aborted, subTaskStates2.get(0));
  }

  @Test
  public void testSubTaskAbortAtPosition() throws InterruptedException {
    UUID taskUUID = UUID.randomUUID();
    UUID subTaskUUID1 = UUID.randomUUID();
    UUID subTaskUUID2 = UUID.randomUUID();

    ObjectNode objectNodeTask = mapper.createObjectNode();
    ObjectNode objectNodeSubTask2 = mapper.createObjectNode();

    ITask task = mockTaskCommon(taskUUID);
    ITask subTask1 = mockTaskCommon(subTaskUUID1);
    ITask subTask2 = mockTaskCommon(subTaskUUID2);

    TaskInfo taskInfo = mockTaskInfoCommon(TaskType.BackupTable, taskUUID, null, objectNodeTask);
    TaskInfo subTaskInfo1 = mockTaskInfoCommon(TaskType.BackupTable, subTaskUUID1, taskUUID, null);
    TaskInfo subTaskInfo2 =
        mockTaskInfoCommon(TaskType.BackupTable, subTaskUUID2, taskUUID, objectNodeSubTask2);

    doReturn(subTaskInfo1, subTaskInfo2).when(taskExecutor).createTaskInfo(any());
    doReturn(0).when(subTaskInfo1).getPosition();
    doReturn(1).when(subTaskInfo2).getPosition();

    doAnswer(
            inv -> {
              // Invoke subTask from the parent task.
              taskExecutor.runSyncSubTask(subTask1);
              taskExecutor.runSyncSubTask(subTask2);
              return null;
            })
        .when(task)
        .run();

    TaskExecutor.TaskRunner taskRunner = taskExecutor.new TaskRunner(task, taskInfo);
    AtomicInteger test = new AtomicInteger(0);
    UUID outTaskUUID =
        taskExecutor.submit(
            taskRunner,
            Executors.newFixedThreadPool(1),
            new TaskExecutionListener() {
              @Override
              public void beforeTask(TaskInfo tf) {
                test.incrementAndGet();
                if (tf.getPosition() == 1) {
                  throw new CancellationException("cancelled");
                }
              }

              @Override
              public void afterTask(TaskInfo taskInfo, Throwable t) {}
            });
    assertEquals(taskUUID, outTaskUUID);
    waitForTask(outTaskUUID);
    // 1 parent task + 2 subtasks.
    assertEquals(3, test.get());

    verify(subTask1, times(1)).run();
    verify(subTask2, times(0)).run();

    ArgumentCaptor<TaskInfo.State> taskArg = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(taskInfo, times(2)).setTaskState(taskArg.capture());
    List<TaskInfo.State> taskStates = taskArg.getAllValues();

    assertEquals(TaskInfo.State.Running, taskStates.get(0));
    assertEquals(TaskInfo.State.Aborted, taskStates.get(1));

    ArgumentCaptor<TaskInfo.State> subTaskArg1 = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(subTaskInfo1, times(2)).setTaskState(subTaskArg1.capture());
    List<TaskInfo.State> subTaskStates1 = subTaskArg1.getAllValues();

    assertEquals(TaskInfo.State.Running, subTaskStates1.get(0));
    assertEquals(TaskInfo.State.Success, subTaskStates1.get(1));

    ArgumentCaptor<TaskInfo.State> subTaskArg2 = ArgumentCaptor.forClass(TaskInfo.State.class);
    verify(subTaskInfo2, atLeastOnce()).setTaskState(subTaskArg2.capture());
    List<TaskInfo.State> subTaskStates2 = subTaskArg2.getAllValues();
    assertEquals(TaskInfo.State.Aborted, subTaskStates2.get(0));
  }
}
