package com.tikuzhushou.project;

import com.tikuzhushou.identity.AppUserService;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;

/** Restores project generation runs after restart and executes them with the owning user context. */
@Service
public class ExamProjectVariantGenerationWorker {
  private static final Logger log = LoggerFactory.getLogger(ExamProjectVariantGenerationWorker.class);

  private final ExamProjectVariantGenerationService generation;
  private final JdbcTemplate jdbc;
  private final AppUserService users;
  private final Executor executor;

  public ExamProjectVariantGenerationWorker(ExamProjectVariantGenerationService generation, JdbcTemplate jdbc,
      AppUserService users, @Qualifier("generationExecutor") Executor executor) {
    this.generation = generation;
    this.jdbc = jdbc;
    this.users = users;
    this.executor = executor;
  }

  public void submit(UUID runId) {
    executor.execute(() -> execute(runId));
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    jdbc.update("update exam_project_generation_items set error_code=case when status='REVIEW_PENDING' then 'REVIEW_SERVICE_ERROR' when status='REJECTED' then 'QUALITY_REJECTED' else null end where error_code='REVIEW_RETRYING'");
    jdbc.update("update exam_project_generation_runs set status='QUEUED',updated_at=current_timestamp where status='RUNNING'");
    jdbc.query("select id from exam_project_generation_runs where status='QUEUED' order by created_at",
        (rs, row) -> rs.getObject(1, UUID.class)).forEach(this::submit);
  }

  private void execute(UUID runId) {
    try {
      String username = jdbc.query("select coalesce(u.username,'admin') from exam_project_generation_runs r join exam_projects p on p.id=r.project_id left join app_users u on u.id=p.owner_id where r.id=?",
          (rs, row) -> rs.getString(1), runId).stream().findFirst().orElse("admin");
      var principal = users.loadUserByUsername(username);
      SecurityContextHolder.getContext().setAuthentication(
          new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
      generation.execute(runId);
      log.info("exam project generation run {} completed", runId);
    } catch (Exception error) {
      log.error("exam project generation run {} failed: {}", runId,
          error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), error);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
