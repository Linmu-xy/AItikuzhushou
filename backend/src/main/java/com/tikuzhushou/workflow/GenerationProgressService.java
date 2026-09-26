package com.tikuzhushou.workflow;

import com.tikuzhushou.core.CoreBusinessService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GenerationProgressService {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseAccessService access;

  public GenerationProgressService(JdbcTemplate jdbc, KnowledgeBaseAccessService access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  public String normalizeKey(String value) {
    String key = Objects.toString(value, "").trim();
    if (key.isBlank()) key = UUID.randomUUID().toString();
    if (!key.matches("[A-Za-z0-9._:-]{8,120}")) throw new IllegalArgumentException("幂等键格式无效");
    return key;
  }

  public Optional<UUID> existing(String key) {
    return jdbc.query("select id from generation_jobs where owner_id=? and idempotency_key=?",
        (rs, n) -> UUID.fromString(rs.getString(1)), access.currentUserId(), key).stream().findFirst();
  }

  public void initialize(UUID id, String key, int total) {
    jdbc.update("update generation_jobs set stage_code='GENERATION_QUEUED',processed_items=0,total_items=?,status_message='AI 命题任务已进入队列',idempotency_key=? where id=?",
        Math.max(0, total), key, id);
  }

  public void update(UUID id, String stage, int progress, int processed, int total, String message,
      String errorCode) {
    jdbc.update("update generation_jobs set stage_code=?,progress=?,processed_items=?,total_items=?,status_message=?,error_code=?,updated_at=? where id=?",
        stage, Math.max(0, Math.min(100, progress)), Math.max(0, processed), Math.max(0, total),
        limit(message, 500), errorCode, java.sql.Timestamp.from(Instant.now()), id);
  }

  public Map<String, Object> enrich(CoreBusinessService.Job job) {
    Progress value = jdbc.query("select stage_code,processed_items,total_items,status_message,error_code,idempotency_key from generation_jobs where id=?",
        (rs, n) -> new Progress(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6)),
        job.id()).stream().findFirst().orElse(new Progress(null, 0, 0, null, null, null));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", job.id()); result.put("type", job.type()); result.put("status", job.status());
    result.put("progress", job.progress()); result.put("request", job.request()); result.put("result", job.result());
    result.put("retryCount", job.retryCount()); result.put("errorMessage", job.errorMessage());
    result.put("createdAt", job.createdAt()); result.put("updatedAt", job.updatedAt());
    result.put("stageCode", Objects.toString(value.stageCode(), "QUEUED"));
    result.put("processedItems", value.processedItems()); result.put("totalItems", value.totalItems());
    result.put("statusMessage", Objects.toString(value.statusMessage(), ""));
    result.put("errorCode", value.errorCode()); result.put("idempotencyKey", value.idempotencyKey());
    SlotStats slots = jdbc.query("select count(*),coalesce(sum(case when status='ACCEPTED' then 1 else 0 end),0),coalesce(sum(case when status in ('RETRYING','EXHAUSTED','REVIEW_PENDING') then 1 else 0 end),0),coalesce(sum(case when status='EXHAUSTED' then 1 else 0 end),0),coalesce(sum(case when status='REVIEW_PENDING' then 1 else 0 end),0),coalesce(sum(attempts),0),coalesce(sum(case when fallback_selected then 1 else 0 end),0) from generation_job_items where job_id=?",
        (rs, n) -> new SlotStats(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)), job.id())
        .stream().findFirst().orElse(new SlotStats(0, 0, 0, 0, 0, 0, 0));
    result.put("plannedItems", slots.planned()); result.put("acceptedItems", slots.accepted());
    result.put("rejectedItems", slots.rejected()); result.put("exhaustedItems", slots.exhausted());
    result.put("reviewPendingItems", slots.reviewPending());
    result.put("generationAttempts", slots.attempts()); result.put("partial", slots.planned() > slots.accepted());
    result.put("fallbackSelectedItems", slots.fallbackSelected());
    String mode = Objects.toString(job.request().get("generationMode"), "BALANCED");
    int concurrency = "FAST".equals(mode) ? 5 : 4;
    long perWaveSeconds = "FAST".equals(mode) ? 30 : "TIERED".equals(mode) ? 120 : 90;
    int remaining = Math.max(0, slots.planned() - slots.accepted());
    long estimate;
    if (!Set.of("QUEUED", "RUNNING").contains(job.status())) estimate = 0;
    else if (slots.accepted() > 0) {
      long elapsed = Math.max(1, Duration.between(job.createdAt(), Instant.now()).toSeconds());
      estimate = Math.max(10, Math.round(elapsed / (double) slots.accepted() * remaining));
    } else estimate = (long) Math.ceil(remaining / (double) concurrency) * perWaveSeconds;
    Integer queueAhead = jdbc.queryForObject("select count(*) from generation_jobs where status='QUEUED' and created_at<?",
        Integer.class, java.sql.Timestamp.from(job.createdAt()));
    result.put("generationMode", mode); result.put("currentConcurrency", concurrency);
    result.put("estimatedRemainingSeconds", estimate);
    result.put("estimatedCompletionAt", estimate == 0 ? null : Instant.now().plusSeconds(estimate));
    result.put("queueAhead", queueAhead == null ? 0 : queueAhead);
    return result;
  }

  public List<Map<String, Object>> enrich(List<CoreBusinessService.Job> jobs) {
    return jobs.stream().map(this::enrich).toList();
  }

  private String limit(String value, int max) {
    if (value == null) return null;
    return value.substring(0, Math.min(value.length(), max));
  }
  private record Progress(String stageCode, int processedItems, int totalItems, String statusMessage,
      String errorCode, String idempotencyKey) { }
  private record SlotStats(int planned, int accepted, int rejected, int exhausted, int reviewPending, int attempts,
      int fallbackSelected) { }
}
