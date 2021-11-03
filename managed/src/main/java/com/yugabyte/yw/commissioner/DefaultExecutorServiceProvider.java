package com.yugabyte.yw.commissioner;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.yugabyte.yw.common.PlatformExecutorFactory;
import com.yugabyte.yw.models.helpers.TaskType;

@Singleton
public class DefaultExecutorServiceProvider implements ExecutorServiceProvider {

  private final PlatformExecutorFactory platformExecutorFactory;

  private final Map<TaskType, ExecutorService> executorServices = new ConcurrentHashMap<>();

  @Inject
  public DefaultExecutorServiceProvider(PlatformExecutorFactory platformExecutorFactory) {
    this.platformExecutorFactory = platformExecutorFactory;
  }

  public ExecutorService getExecutorService(TaskType taskType) {
    ExecutorService executorService = executorServices.get(taskType);
    if (executorService != null) {
      return executorService;
    }
    synchronized (executorServices) {
      executorService = executorServices.get(taskType);
      if (executorService != null) {
        return executorService;
      }
      ThreadFactory namedThreadFactory =
          new ThreadFactoryBuilder().setNameFormat("TaskPool-" + taskType + "-%d").build();
      executorService = platformExecutorFactory.createExecutor("task", namedThreadFactory);
      executorServices.put(taskType, executorService);
    }
    return executorService;
  }
}
