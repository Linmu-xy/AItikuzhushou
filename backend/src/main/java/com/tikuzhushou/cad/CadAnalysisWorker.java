package com.tikuzhushou.cad;

import com.tikuzhushou.identity.AppUserService;
import com.tikuzhushou.workflow.WorkflowTaskService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Queue worker isolated from the existing document/question workers. */
@Service
public class CadAnalysisWorker {
  public static final String TASK_TYPE = "CAD_ANALYSIS";
  private static final Logger log = LoggerFactory.getLogger(CadAnalysisWorker.class);
  private final WorkflowTaskService tasks;
  private final CadMaterialService materials;
  private final CadAnalysisService analysis;
  private final StringRedisTemplate redis;
  private final Executor executor;
  private final JdbcTemplate jdbc;
  private final AppUserService users;
  private final boolean redisQueue;
  private final String queueKey;

  public CadAnalysisWorker(WorkflowTaskService tasks, CadMaterialService materials, CadAnalysisService analysis,
      StringRedisTemplate redis, @Qualifier("generationExecutor") Executor executor, JdbcTemplate jdbc,
      AppUserService users, @Value("${app.queue.provider:local}") String provider,
      @Value("${app.queue.cad-key:tiku:cad:jobs}") String queueKey) {
    this.tasks = tasks;
    this.materials = materials;
    this.analysis = analysis;
    this.redis = redis;
    this.executor = executor;
    this.jdbc = jdbc;
    this.users = users;
    this.redisQueue = "redis".equalsIgnoreCase(provider);
    this.queueKey = queueKey;
  }

  public WorkflowTaskService.Task submit(UUID materialId, String idempotencyKey) {
    materials.get(materialId);
    var created = tasks.create(TASK_TYPE, materialId, idempotencyKey, "模型或工程图解析任务已进入队列");
    if (created.created()) {
      analysis.createJob(materialId, created.task().id());
      enqueue(created.task().id());
    }
    return created.task();
  }

  private void enqueue(UUID taskId) {
    if (redisQueue) redis.opsForList().rightPush(queueKey, taskId.toString());
    else execute(taskId);
  }

  @Scheduled(fixedDelayString = "${APP_QUEUE_POLL_MS:250}")
  public void drain() {
    if (!redisQueue) return;
    for (int index = 0; index < 4; index++) {
      String raw = redis.opsForList().leftPop(queueKey);
      if (raw == null) return;
      try { execute(UUID.fromString(raw)); } catch (IllegalArgumentException ignored) { }
    }
  }

  private void execute(UUID taskId) {
    executor.execute(() -> {
      if (!tasks.claim(taskId, "CAD_PARSE", "CAD Worker 已领取解析任务")) return;
      try {
        var task = tasks.systemGet(taskId);
        restoreOwner(task.ownerId());
        var job = analysis.jobByTask(taskId);
        Map<String, Object> result = analysis.run(job);
        tasks.result(taskId, result);
        String status = String.valueOf(result.getOrDefault("status", "FAILED"));
        if ("FAILED".equals(status)) {
          tasks.update(taskId, "FAILED", "CAD_PARSE_FAILED", 100, 0, 0,
              String.valueOf(result.getOrDefault("message", "CAD Worker 解析失败")),
              String.valueOf(result.getOrDefault("errorCode", "CAD_WORKER_FAILED")),
              String.valueOf(result.getOrDefault("message", "CAD Worker 解析失败")));
        } else {
          tasks.update(taskId, "SUCCEEDED", status, 100, 1, 1,
              "CAD 文件分析完成：" + status, null, null);
        }
      } catch (Exception error) {
        tasks.update(taskId, "FAILED", "CAD_PARSE_FAILED", 100, 0, 0,
            "CAD 文件处理失败", "CAD_PARSE_FAILED", rootMessage(error));
        log.error("CAD analysis task {} failed: {}", taskId, rootMessage(error), error);
      } finally {
        SecurityContextHolder.clearContext();
      }
    });
  }

  private void restoreOwner(UUID ownerId) {
    String username = jdbc.queryForObject("select username from app_users where id=?", String.class, ownerId);
    var principal = users.loadUserByUsername(username);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() { tasks.recoverable(TASK_TYPE).forEach(this::enqueue); }

  private String rootMessage(Throwable error) {
    Throwable value = error;
    while (value.getCause() != null) value = value.getCause();
    return value.getMessage() == null || value.getMessage().isBlank() ? value.getClass().getSimpleName() : value.getMessage();
  }
}
