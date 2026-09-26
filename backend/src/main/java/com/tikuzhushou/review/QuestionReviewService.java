package com.tikuzhushou.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.core.CoreBusinessService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionReviewService {
  private static final Set<String> STATUSES = Set.of("AI_DRAFT", "PENDING_REVIEW", "APPROVED", "REJECTED", "LOCKED", "OBSOLETE");
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final CoreBusinessService core;
  private final KnowledgeBaseAccessService access;

  public QuestionReviewService(JdbcTemplate jdbc, ObjectMapper json, CoreBusinessService core,
      KnowledgeBaseAccessService access) {
    this.jdbc = jdbc; this.json = json; this.core = core; this.access = access;
  }

  @Transactional
  public List<Item> list(UUID jobId, String status) {
    seed(jobId);
    String filter = Objects.toString(status, "").trim().toUpperCase();
    if (!filter.isBlank() && !STATUSES.contains(filter)) throw new IllegalArgumentException("审核状态无效");
    String sql = "select id,job_id,sequence_no,review_status,locked,comment,question_json,version,reviewer_id,created_at,updated_at from question_reviews where job_id=?"
        + (filter.isBlank() ? "" : " and review_status=?") + " order by sequence_no";
    return filter.isBlank() ? jdbc.query(sql, this::map, jobId) : jdbc.query(sql, this::map, jobId, filter);
  }

  @Transactional
  public Item update(UUID jobId, int sequence, Update request) {
    seed(jobId);
    Item current = find(jobId, sequence);
    String status = Objects.toString(request.status(), current.status()).trim().toUpperCase();
    if (!STATUSES.contains(status)) throw new IllegalArgumentException("审核状态无效");
    boolean nextLocked = request.locked() == null ? current.locked() : request.locked();
    if (current.locked() && request.question() != null && !request.question().isEmpty()) {
      throw new IllegalArgumentException("题目已锁定，请先解除锁定再编辑");
    }
    Map<String, Object> question = current.question();
    if (request.question() != null && !request.question().isEmpty()) question = core.updateQuestion(jobId, sequence, request.question());
    if (nextLocked) status = "LOCKED";
    else if ("LOCKED".equals(status)) status = "APPROVED";
    String comment = Objects.toString(request.comment(), current.comment()).trim();
    UUID reviewer = access.currentUserId(); int version = current.version() + 1; Instant now = Instant.now();
    try {
      jdbc.update("update question_reviews set reviewer_id=?,review_status=?,locked=?,comment=?,question_json=?,version=?,updated_at=? where job_id=? and sequence_no=?",
          reviewer, status, nextLocked, comment, json.writeValueAsString(question), version, Timestamp.from(now), jobId, sequence);
      UUID reviewId = current.id();
      jdbc.update("insert into question_review_events(id,review_id,actor_id,action,comment,snapshot_json,created_at) values(?,?,?,?,?,?,?)",
          UUID.randomUUID(), reviewId, reviewer, request.question() == null || request.question().isEmpty() ? "STATUS_CHANGED" : "QUESTION_EDITED",
          comment, json.writeValueAsString(question), Timestamp.from(now));
    } catch (Exception error) { throw new IllegalStateException("保存题目审核结果失败", error); }
    return find(jobId, sequence);
  }

  public Summary summary(UUID jobId) {
    seed(jobId);
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String status : STATUSES) counts.put(status, 0);
    jdbc.query("select review_status,count(*) from question_reviews where job_id=? group by review_status",
        rs -> { counts.put(rs.getString(1), rs.getInt(2)); }, jobId);
    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
    int deliveryReady = counts.get("APPROVED") + counts.get("LOCKED");
    return new Summary(jobId, total, deliveryReady, total - deliveryReady, counts, total > 0 && total == deliveryReady);
  }

  public List<Event> events(UUID jobId, int sequence) {
    seed(jobId); Item item = find(jobId, sequence);
    return jdbc.query("select e.id,e.action,e.comment,e.snapshot_json,e.created_at,u.username from question_review_events e left join app_users u on e.actor_id=u.id where e.review_id=? order by e.created_at desc",
        (rs, row) -> new Event(UUID.fromString(rs.getString(1)), rs.getString(2), Objects.toString(rs.getString(3), ""),
            parse(rs.getString(4)), Objects.toString(rs.getString(6), "系统"), rs.getTimestamp(5).toInstant()), item.id());
  }

  private void seed(UUID jobId) {
    var job = core.getJob(jobId);
    if (!"QUESTION_BANK".equals(job.type())) throw new IllegalArgumentException("只能审核题库任务");
    UUID owner = jdbc.queryForObject("select owner_id from generation_jobs where id=?", UUID.class, jobId);
    Instant now = Instant.now();
    for (Map<String, Object> question : job.result()) {
      int sequence = number(question.get("sequence"));
      Integer existing = jdbc.queryForObject("select count(*) from question_reviews where job_id=? and sequence_no=?", Integer.class, jobId, sequence);
      if (existing != null && existing > 0) continue;
      try { jdbc.update("insert into question_reviews(id,job_id,sequence_no,owner_id,review_status,locked,comment,question_json,version,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(), jobId, sequence, owner, "AI_DRAFT", false, "", json.writeValueAsString(question), 1,
          Timestamp.from(now), Timestamp.from(now)); }
      catch (org.springframework.dao.DuplicateKeyException ignored) { }
      catch (Exception error) { throw new IllegalStateException("初始化题目审核数据失败", error); }
    }
  }

  private Item find(UUID jobId, int sequence) {
    var rows = jdbc.query("select id,job_id,sequence_no,review_status,locked,comment,question_json,version,reviewer_id,created_at,updated_at from question_reviews where job_id=? and sequence_no=?",
        this::map, jobId, sequence);
    if (rows.isEmpty()) throw new IllegalArgumentException("审核题目不存在");
    return rows.getFirst();
  }
  private Item map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Item(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("job_id")),
        rs.getInt("sequence_no"), rs.getString("review_status"), rs.getBoolean("locked"),
        Objects.toString(rs.getString("comment"), ""), parse(rs.getString("question_json")), rs.getInt("version"),
        rs.getObject("reviewer_id") == null ? null : rs.getObject("reviewer_id", UUID.class),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
  }
  private Map<String, Object> parse(String value) {
    try { return json.readValue(value, new TypeReference<>() { }); }
    catch (Exception error) { throw new IllegalStateException("审核题目快照损坏", error); }
  }
  private int number(Object value) {
    try { return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value)); }
    catch (Exception error) { throw new IllegalArgumentException("题目序号无效"); }
  }

  public record Update(String status, Boolean locked, String comment, Map<String, Object> question) { }
  public record Item(UUID id, UUID jobId, int sequence, String status, boolean locked, String comment,
      Map<String, Object> question, int version, UUID reviewerId, Instant createdAt, Instant updatedAt) { }
  public record Summary(UUID jobId, int total, int deliveryReady, int pending, Map<String, Integer> statusCounts,
      boolean readyForDelivery) { }
  public record Event(UUID id, String action, String comment, Map<String, Object> snapshot, String actor, Instant createdAt) { }
}
