package com.tikuzhushou.workflow;

import com.tikuzhushou.document.DocumentIntakeService;
import com.tikuzhushou.document.DocumentParsingService;
import com.tikuzhushou.identity.AppUserService;
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

@Service
public class DocumentWorkflowWorker {
  private static final Logger log = LoggerFactory.getLogger(DocumentWorkflowWorker.class);
  private final WorkflowTaskService tasks;
  private final DocumentParsingService parsing;
  private final DocumentIntakeService intake;
  private final StringRedisTemplate redis;
  private final Executor executor;
  private final JdbcTemplate jdbc;
  private final AppUserService users;
  private final boolean redisQueue;
  private final String queueKey;

  public DocumentWorkflowWorker(WorkflowTaskService tasks, DocumentParsingService parsing,
      DocumentIntakeService intake, StringRedisTemplate redis,
      @Qualifier("generationExecutor") Executor executor, JdbcTemplate jdbc, AppUserService users,
      @Value("${app.queue.provider:local}") String provider,
      @Value("${app.queue.document-key:tiku:document:jobs}") String queueKey) {
    this.tasks = tasks;
    this.parsing = parsing;
    this.intake = intake;
    this.redis = redis;
    this.executor = executor;
    this.jdbc = jdbc;
    this.users = users;
    this.redisQueue = "redis".equalsIgnoreCase(provider);
    this.queueKey = queueKey;
  }

  public WorkflowTaskService.Task submit(UUID documentId, String idempotencyKey) {
    intake.get(documentId);
    var created = tasks.create("DOCUMENT_PARSE", documentId, idempotencyKey, "文档解析任务已进入队列");
    if (created.created()) enqueue(created.task().id());
    return created.task();
  }

  private void enqueue(UUID id) {
    if (redisQueue) redis.opsForList().rightPush(queueKey, id.toString());
    else execute(id);
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
      if (!tasks.claim(taskId)) return;
      WorkflowTaskService.Task task = tasks.systemGet(taskId);
      try {
        restoreOwner(task.ownerId());
        jdbc.update("update source_documents set status='PROCESSING',updated_at=? where id=?",
            java.sql.Timestamp.from(java.time.Instant.now()), task.resourceId());
        var result = parsing.parse(task.resourceId(), (stage, progress, processed, total, message) -> {
          if (tasks.cancelled(taskId)) throw new TaskCancelledException();
          tasks.update(taskId, "RUNNING", stage, progress, processed, total, message, null, null);
        });
        if (tasks.cancelled(taskId)) throw new TaskCancelledException();
        if (!"PARSED".equals(result.status())) {
          tasks.update(taskId, "FAILED", "QUALITY_REJECTED", 100, result.chunks().size(), result.chunks().size(),
              "OCR/解析质量未达到进入职业标准抽取的要求", "QUALITY_REJECTED",
              String.join("；", result.warnings()));
          log.warn("document parse quality rejected task={} documentId={} chunks={} warnings={}",
              taskId, task.resourceId(), result.chunks().size(), result.warnings());
          return;
        }
        tasks.update(taskId, "SUCCEEDED", "SUCCEEDED", 100, result.chunks().size(), result.chunks().size(),
            "文档解析、分块和向量化完成", null, null);
        log.info("document parse succeeded task={} documentId={} chunks={}",
            taskId, task.resourceId(), result.chunks().size());
      } catch (TaskCancelledException cancelled) {
        tasks.update(taskId, "CANCELLED", "CANCELLED", 100, 0, 0, "任务已取消，可重新提交解析", null, null);
        jdbc.update("update source_documents set status='UPLOADED',updated_at=? where id=?",
            java.sql.Timestamp.from(java.time.Instant.now()), task.resourceId());
        log.info("document parse task {} cancelled by user", taskId);
      } catch (Exception error) {
        String code = classify(error);
        tasks.update(taskId, "FAILED", code, 100, 0, 0, "文档处理失败", code, rootMessage(error));
        jdbc.update("update source_documents set status='PARSE_FAILED',updated_at=? where id=?",
            java.sql.Timestamp.from(java.time.Instant.now()), task.resourceId());
        log.error("document parse task {} documentId={} failed [{}]: {}",
            taskId, task.resourceId(), code, rootMessage(error), error);
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

  private String classify(Exception error) {
    String message = rootMessage(error);
    if (message.contains("额度")) return "MODEL_QUOTA_EXCEEDED";
    if (message.contains("模型") || message.contains("DeepSeek")) return "MODEL_UNAVAILABLE";
    if (message.contains("OCR") || message.contains("文字")) return "QUALITY_REJECTED";
    return "FAILED";
  }

  private String rootMessage(Throwable error) {
    Throwable value = error;
    while (value.getCause() != null) value = value.getCause();
    String message = value.getMessage();
    return message == null || message.isBlank() ? value.getClass().getSimpleName() : message;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() { tasks.recoverable().forEach(this::enqueue); }

  private static final class TaskCancelledException extends RuntimeException { }
}
