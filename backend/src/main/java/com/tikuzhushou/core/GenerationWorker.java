package com.tikuzhushou.core;

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

/** Redis queue worker restores the persisted task owner before any secured data or model call. */
@Service
public class GenerationWorker {
  private static final Logger log = LoggerFactory.getLogger(GenerationWorker.class);
  private final CoreBusinessService core;
  private final StringRedisTemplate redis;
  private final Executor executor;
  private final boolean redisQueue;
  private final String queueKey;
  private final JdbcTemplate jdbc;
  private final AppUserService users;

  public GenerationWorker(CoreBusinessService core, StringRedisTemplate redis,
      @Qualifier("generationExecutor") Executor executor,
      @Value("${app.queue.provider:local}") String provider,
      @Value("${app.queue.key:tiku:generation:jobs}") String queueKey,
      JdbcTemplate jdbc, AppUserService users) {
    this.core = core;
    this.redis = redis;
    this.executor = executor;
    this.redisQueue = "redis".equalsIgnoreCase(provider);
    this.queueKey = queueKey;
    this.jdbc = jdbc;
    this.users = users;
  }

  public void submit(UUID id) {
    if (redisQueue) {
      redis.opsForList().rightPush(queueKey, id.toString());
      log.debug("question generation job {} enqueued", id);
    } else {
      execute(id);
    }
  }

  @Scheduled(fixedDelayString = "${APP_QUEUE_POLL_MS:250}")
  public void drainRedisQueue() {
    if (!redisQueue) return;
    for (int index = 0; index < 8; index++) {
      String raw = redis.opsForList().leftPop(queueKey);
      if (raw == null) return;
      try { execute(UUID.fromString(raw)); } catch (IllegalArgumentException ignored) { }
    }
  }

  private void execute(UUID id) {
    executor.execute(() -> {
      try {
        String username = jdbc.query("select coalesce(u.username,'admin') from generation_jobs g left join app_users u on u.id=g.owner_id where g.id=?",
            (rs, n) -> rs.getString(1), id).stream().findFirst().orElse("admin");
        var principal = users.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        core.executeQuestionJob(id);
        log.info("question generation job {} completed", id);
      } catch (Exception error) {
        // CoreBusinessService persists failure details for the task center; log the full
        // stack here because the persisted error_message is a single-line summary only.
        log.error("question generation job {} failed: {}", id,
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), error);
      } finally {
        SecurityContextHolder.clearContext();
      }
    });
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() { core.recoverableJobs().forEach(this::submit); }
}
