package com.tikuzhushou.fast;

import com.tikuzhushou.identity.AppUserService;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Restores opt-in fast document jobs after a process restart. */
@Service
public class FastDocumentGenerationWorker {
  private static final Logger log = LoggerFactory.getLogger(FastDocumentGenerationWorker.class);
  private final FastDocumentGenerationService service;
  private final JdbcTemplate jdbc;
  private final AppUserService users;
  private final Executor executor;
  private final boolean enabled;
  private final Set<UUID> activeJobs = ConcurrentHashMap.newKeySet();

  public FastDocumentGenerationWorker(FastDocumentGenerationService service, JdbcTemplate jdbc,
      AppUserService users, @Qualifier("generationExecutor") Executor executor,
      @Value("${app.fast-document.enabled:false}") boolean enabled) {
    this.service = service; this.jdbc = jdbc; this.users = users; this.executor = executor; this.enabled = enabled;
  }

  public void submit(UUID jobId) {
    if (!enabled || !activeJobs.add(jobId)) return;
    executor.execute(() -> execute(jobId));
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    if (!enabled) return;
    jdbc.update("update fast_generation_jobs set status='QUEUED',updated_at=current_timestamp where status='RUNNING'");
    jdbc.query("select id from fast_generation_jobs where status='QUEUED' order by created_at",
        (rs, row) -> rs.getObject(1, UUID.class)).forEach(this::submit);
  }

  private void execute(UUID jobId) {
    try {
      String username = jdbc.query("select coalesce(u.username,'admin') from fast_generation_jobs g left join app_users u on u.id=g.owner_id where g.id=?",
          (rs, row) -> rs.getString(1), jobId).stream().findFirst().orElse("admin");
      var principal = users.loadUserByUsername(username);
      SecurityContextHolder.getContext().setAuthentication(
          new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
      service.execute(jobId);
      log.info("fast document generation job {} completed", jobId);
    } catch (Exception error) {
      log.error("fast document generation job {} failed: {}", jobId,
          error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), error);
    } finally {
      SecurityContextHolder.clearContext();
      activeJobs.remove(jobId);
    }
  }
}
