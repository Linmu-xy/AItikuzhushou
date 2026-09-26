package com.tikuzhushou.project;

import com.tikuzhushou.identity.AppUserService;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable async worker for project delivery exports. */
@Service
public class ExamProjectExportWorker {
  private static final Logger log = LoggerFactory.getLogger(ExamProjectExportWorker.class);
  private final ExamProjectExportService exports;
  private final StringRedisTemplate redis;
  private final Executor executor;
  private final JdbcTemplate jdbc;
  private final AppUserService users;
  private final boolean redisQueue;
  private final String queueKey;

  public ExamProjectExportWorker(ExamProjectExportService exports, StringRedisTemplate redis,
      @Qualifier("generationExecutor") Executor executor, JdbcTemplate jdbc, AppUserService users,
      @Value("${app.queue.provider:local}") String provider,
      @Value("${app.queue.exam-project-export-key:tiku:exam-project:exports}") String queueKey) {
    this.exports = exports;
    this.redis = redis;
    this.executor = executor;
    this.jdbc = jdbc;
    this.users = users;
    this.redisQueue = "redis".equalsIgnoreCase(provider);
    this.queueKey = queueKey;
  }

  public void submit(UUID exportId) {
    if (redisQueue) redis.opsForList().rightPush(queueKey, exportId.toString());
    else execute(exportId);
  }

  @Scheduled(fixedDelayString = "${APP_QUEUE_POLL_MS:250}")
  public void drain() {
    if (!redisQueue) return;
    for (int index = 0; index < 4; index++) {
      String value = redis.opsForList().leftPop(queueKey);
      if (value == null) return;
      try { execute(UUID.fromString(value)); } catch (IllegalArgumentException ignored) { }
    }
  }

  private void execute(UUID exportId) {
    executor.execute(() -> {
      try {
        UUID owner = jdbc.queryForObject("select p.owner_id from exam_project_export_runs e join exam_projects p on p.id=e.project_id where e.id=?", UUID.class, exportId);
        restoreOwner(owner);
        exports.execute(exportId);
        log.info("exam project export={} completed", exportId);
      } catch (Exception error) {
        exports.fail(exportId, rootMessage(error));
        log.error("exam project export={} failed: {}", exportId, rootMessage(error), error);
      } finally {
        SecurityContextHolder.clearContext();
      }
    });
  }

  private void restoreOwner(UUID ownerId) {
    String username = jdbc.queryForObject("select username from app_users where id=?", String.class, ownerId);
    var principal = users.loadUserByUsername(username);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        principal, null, principal.getAuthorities()));
  }

  private String rootMessage(Throwable error) {
    Throwable value = error;
    while (value.getCause() != null) value = value.getCause();
    return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    exports.recoverable().forEach(this::submit);
  }
}
