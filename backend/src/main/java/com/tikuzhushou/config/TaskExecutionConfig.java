package com.tikuzhushou.config;

import java.util.concurrent.Executor;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
public class TaskExecutionConfig {
  @Bean("generationExecutor")
  Executor generationExecutor() { return executor("generation-", 2, 4); }

  /** Dedicated bounded pool so parallel model calls cannot starve queue consumption. */
  @Bean("questionGenerationExecutor")
  Executor questionGenerationExecutor(@Value("${app.ai.question-concurrency:5}") int concurrency) {
    int size = Math.max(1, Math.min(concurrency, 12));
    return executor("question-ai-", size, size);
  }

  private Executor executor(String prefix, int core, int max) {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(core); executor.setMaxPoolSize(max); executor.setQueueCapacity(200);
    executor.setThreadNamePrefix(prefix); executor.setTaskDecorator(contextDecorator()); executor.initialize();
    return executor;
  }

  /** Carries the caller's security context and trace id into asynchronous task threads. */
  private TaskDecorator contextDecorator() {
    return task -> {
      SecurityContext parent = SecurityContextHolder.getContext();
      SecurityContext copy = SecurityContextHolder.createEmptyContext();
      copy.setAuthentication(parent.getAuthentication());
      Map<String, String> mdc = MDC.getCopyOfContextMap();
      return () -> {
        try {
          if (mdc != null) MDC.setContextMap(mdc);
          SecurityContextHolder.setContext(copy);
          task.run();
        } finally {
          MDC.clear();
          SecurityContextHolder.clearContext();
        }
      };
    };
  }
}
