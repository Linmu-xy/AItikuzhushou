package com.tikuzhushou.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Teacher review boundary for project-generated questions. It is separate from the legacy question-bank review flow. */
@Service
public class ExamProjectQuestionReviewService {
  private static final Set<String> DECISIONS = Set.of("SAVE", "SAVE_DRAFT", "APPROVE", "REJECT", "REOPEN");
  private static final Set<String> CHOICE_TYPES = Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE");

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final ExamProjectService projects;
  private final KnowledgeBaseAccessService access;

  public ExamProjectQuestionReviewService(JdbcTemplate jdbc, ObjectMapper json,
      ExamProjectService projects, KnowledgeBaseAccessService access) {
    this.jdbc = jdbc;
    this.json = json;
    this.projects = projects;
    this.access = access;
  }

  @Transactional
  public ReviewItem review(UUID projectId, UUID runId, UUID itemId, ReviewRequest request) {
    if (request == null) throw new IllegalArgumentException("审核请求不能为空");
    ItemState current = load(projectId, runId, itemId);
    if ("REVIEW_RETRYING".equals(current.errorCode())) throw new IllegalArgumentException("AI 正在重新审题，请稍后再编辑");
    ensureHistory(current);
    if (request.expectedVersion() == null || request.expectedVersion() != current.questionVersion()) {
      throw new IllegalStateException("题目已被其他操作更新，请刷新后再审核");
    }
    String decision = normalizeDecision(request.decision());
    if (Set.of("PLANNED", "GENERATING", "REVIEW_PENDING", "FAILED").contains(current.status())) {
      throw new IllegalArgumentException("当前题目尚未进入人工审核状态");
    }
    if ("REOPEN".equals(decision) && !"APPROVED".equals(current.status())) {
      throw new IllegalArgumentException("只有已通过的题目可以重新打开审核");
    }
    if ("APPROVE".equals(decision) && current.question().isEmpty() && isEmpty(request.question())) {
      throw new IllegalArgumentException("题目内容为空，不能审核通过");
    }
    if ("REJECT".equals(decision) && clean(request.comment()).isBlank()) {
      throw new IllegalArgumentException("驳回题目时必须填写原因");
    }
    if ("APPROVED".equals(current.status()) && !"REOPEN".equals(decision)) {
      throw new IllegalArgumentException("题目已通过，请先重新打开审核再编辑或驳回");
    }

    if ("REOPEN".equals(decision) && !isEmpty(request.question())) throw new IllegalArgumentException("重新打开审核时不能同时修改题目");
    boolean edited = !isEmpty(request.question());
    Map<String, Object> nextQuestion = edited ? mergeQuestion(current, request.question()) : current.question();
    if ("APPROVE".equals(decision)) validateQuestion(current, nextQuestion);
    String nextStatus = nextStatus(current.status(), decision);
    String comment = request.comment() == null ? current.reviewComment() : clean(request.comment());
    int nextVersion = edited ? current.questionVersion() + 1 : current.questionVersion();
    Instant now = Instant.now();
    UUID actor = access.currentUserId();
    int changed = jdbc.update("update exam_project_generation_items set status=?,question_json=?,question_version=?,reviewer_id=?,review_comment=?,reviewed_at=?,updated_at=? where id=? and run_id=? and question_version=? and status=? and (error_code is null or error_code<>'REVIEW_RETRYING')",
        nextStatus, write(nextQuestion), nextVersion, actor, nullIfBlank(comment), Timestamp.from(now), Timestamp.from(now), itemId, runId, current.questionVersion(), current.status());
    if (changed != 1) throw new IllegalStateException("题目已被其他操作更新，请刷新后再审核");
    if (edited) {
      jdbc.update("insert into exam_project_question_versions(id,generation_item_id,version,question_json,design_json,evidence_json,change_type,change_summary,actor_id,created_at) values(?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(), itemId, nextVersion, write(nextQuestion), current.designJson(), current.evidenceJson(),
          "TEACHER_EDITED", nullIfBlank(comment), actor, Timestamp.from(now));
    }
    Map<String, Object> eventSnapshot = new LinkedHashMap<>();
    eventSnapshot.put("question", nextQuestion);
    eventSnapshot.put("status", nextStatus);
    eventSnapshot.put("version", nextVersion);
    jdbc.update("insert into exam_project_question_review_events(id,generation_item_id,actor_id,action,from_status,to_status,question_version,comment,snapshot_json,created_at) values(?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(), itemId, actor, action(decision, edited), current.status(), nextStatus, nextVersion,
        nullIfBlank(comment), write(eventSnapshot), Timestamp.from(now));
    refreshRun(runId);
    return view(load(projectId, runId, itemId));
  }

  public List<QuestionVersion> versions(UUID projectId, UUID runId, UUID itemId) {
    ItemState current = load(projectId, runId, itemId);
    ensureHistory(current);
    return jdbc.query("select id,version,question_json,design_json,evidence_json,change_type,change_summary,actor_id,created_at from exam_project_question_versions where generation_item_id=? order by version desc",
        (rs, row) -> new QuestionVersion(rs.getObject("id", UUID.class), rs.getInt("version"),
            readMap(rs.getString("question_json")), readMap(rs.getString("design_json")), readMap(rs.getString("evidence_json")),
            rs.getString("change_type"), Objects.toString(rs.getString("change_summary"), ""),
            nullableUuid(rs.getObject("actor_id")), rs.getTimestamp("created_at").toInstant()), itemId);
  }

  public List<ReviewEvent> events(UUID projectId, UUID runId, UUID itemId) {
    ItemState current = load(projectId, runId, itemId);
    return jdbc.query("select e.id,e.actor_id,e.action,e.from_status,e.to_status,e.question_version,e.comment,e.snapshot_json,e.created_at,coalesce(u.username,'系统') actor_name from exam_project_question_review_events e left join app_users u on u.id=e.actor_id where e.generation_item_id=? order by e.created_at desc",
        (rs, row) -> new ReviewEvent(rs.getObject("id", UUID.class), nullableUuid(rs.getObject("actor_id")),
            rs.getString("actor_name"), rs.getString("action"), rs.getString("from_status"), rs.getString("to_status"),
            rs.getInt("question_version"), Objects.toString(rs.getString("comment"), ""),
            readMap(rs.getString("snapshot_json")), rs.getTimestamp("created_at").toInstant()), current.id());
  }

  public ExportReadiness exportReadiness(UUID projectId, UUID runId) {
    ItemState current = loadAny(projectId, runId);
    Integer total = jdbc.queryForObject("select count(*) from exam_project_generation_items where run_id=?", Integer.class, runId);
    Integer approved = jdbc.queryForObject("select count(*) from exam_project_generation_items where run_id=? and status='APPROVED'", Integer.class, runId);
    Integer failed = jdbc.queryForObject("select count(*) from exam_project_generation_items where run_id=? and status in ('FAILED','REJECTED')", Integer.class, runId);
    int totalCount = total == null ? 0 : total;
    int approvedCount = approved == null ? 0 : approved;
    int failedCount = failed == null ? 0 : failed;
    List<String> blockers = new ArrayList<>();
    if (totalCount == 0) blockers.add("没有可导出的题目");
    if (approvedCount < totalCount) blockers.add("仍有 " + (totalCount - approvedCount) + " 道题目未人工审核通过");
    if (failedCount > 0) blockers.add("有 " + failedCount + " 道题目被驳回或生成失败");
    return new ExportReadiness(runId, totalCount, approvedCount, failedCount, blockers.isEmpty(), List.copyOf(blockers));
  }

  private ItemState load(UUID projectId, UUID runId, UUID itemId) {
    projects.get(projectId);
    return jdbc.query("select i.id,i.run_id,i.variant_item_id,i.variant_no,i.variant_label,i.sequence_no,i.question_type,i.type_label,i.difficulty,i.points,i.status,i.question_json,i.design_json,i.evidence_json,i.review_json,i.error_code,i.error_message,i.question_version,i.reviewer_id,i.review_comment,i.reviewed_at from exam_project_generation_items i join exam_project_generation_runs r on r.id=i.run_id where r.project_id=? and r.id=? and i.id=?",
        (rs, row) -> mapState(rs), projectId, runId, itemId).stream().findFirst()
        .orElseThrow(() -> new IllegalArgumentException("项目审核题目不存在"));
  }

  private ItemState loadAny(UUID projectId, UUID runId) {
    projects.get(projectId);
    return jdbc.query("select i.id,i.run_id,i.variant_item_id,i.variant_no,i.variant_label,i.sequence_no,i.question_type,i.type_label,i.difficulty,i.points,i.status,i.question_json,i.design_json,i.evidence_json,i.review_json,i.error_code,i.error_message,i.question_version,i.reviewer_id,i.review_comment,i.reviewed_at from exam_project_generation_items i join exam_project_generation_runs r on r.id=i.run_id where r.project_id=? and r.id=? order by i.sequence_no limit 1",
        (rs, row) -> mapState(rs), projectId, runId).stream().findFirst()
        .orElseThrow(() -> new IllegalArgumentException("项目生成任务不存在"));
  }

  private ItemState mapState(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ItemState(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
        rs.getObject("variant_item_id", UUID.class), rs.getInt("variant_no"), rs.getString("variant_label"),
        rs.getInt("sequence_no"), rs.getString("question_type"), rs.getString("type_label"), rs.getString("difficulty"),
        rs.getInt("points"), rs.getString("status"), readMap(rs.getString("question_json")),
        Objects.toString(rs.getString("design_json"), "{}"), Objects.toString(rs.getString("evidence_json"), "{}"),
        readMap(rs.getString("review_json")), Objects.toString(rs.getString("error_code"), ""),
        Objects.toString(rs.getString("error_message"), ""), rs.getInt("question_version"),
        nullableUuid(rs.getObject("reviewer_id")), Objects.toString(rs.getString("review_comment"), ""),
        rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant());
  }

  private void ensureHistory(ItemState state) {
    if (state.question().isEmpty()) return;
    Integer count = jdbc.queryForObject("select count(*) from exam_project_question_versions where generation_item_id=?", Integer.class, state.id());
    if (count != null && count > 0) return;
    jdbc.update("insert into exam_project_question_versions(id,generation_item_id,version,question_json,design_json,evidence_json,change_type,change_summary,actor_id,created_at) values(?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(), state.id(), Math.max(1, state.questionVersion()), write(state.question()), state.designJson(), state.evidenceJson(),
        "AI_GENERATED", "AI 生成结果快照", state.reviewerId(), Timestamp.from(state.reviewedAt() == null ? Instant.now() : state.reviewedAt()));
  }

  private Map<String, Object> mergeQuestion(ItemState current, Map<String, Object> submitted) {
    Map<String, Object> next = new LinkedHashMap<>(current.question());
    submitted.forEach((key, value) -> {
      if (key != null && !key.startsWith("_")) next.put(key, value);
    });
    next.put("sequence", current.sequenceNo());
    next.put("type", current.questionType());
    next.put("points", current.points());
    if (next.get("scoringItems") instanceof List<?> rows && !rows.isEmpty()) {
      next.put("scoringRubric", rows.stream().filter(Map.class::isInstance).map(value -> {
        Map<?,?> row=(Map<?,?>) value;
        return text(row.get("criterion")) + "（" + text(row.get("points")) + "分）";
      }).collect(java.util.stream.Collectors.joining("；")));
    }
    return next;
  }

  private void validateQuestion(ItemState current, Map<String, Object> question) {
    String pipeline = text(readMap(current.designJson()).get("pipelineVersion"));
    if (!KnowledgeAssessmentPlanningService.isOpen(pipeline)
        && (text(question.get("sourceRef")).isBlank() || text(question.get("sourceExcerpt")).isBlank())) {
      throw new IllegalArgumentException("不得删除资料来源和来源片段");
    }
    var blockers = QuestionQualityChecks.inspect(question,current.questionType(),current.points()).stream()
        .filter(issue -> "ERROR".equals(issue.severity())).map(QuestionQualityChecks.Issue::message).toList();
    if (!blockers.isEmpty()) throw new IllegalArgumentException(String.join("；",blockers));
  }

  private void refreshRun(UUID runId) {
    jdbc.update("update exam_project_generation_runs set processed_count=(select count(*) from exam_project_generation_items where run_id=? and status not in ('PLANNED','GENERATING')),review_required_count=(select count(*) from exam_project_generation_items where run_id=? and status='REVIEW_REQUIRED'),review_pending_count=(select count(*) from exam_project_generation_items where run_id=? and status='REVIEW_PENDING'),failed_count=(select count(*) from exam_project_generation_items where run_id=? and status in ('FAILED','REJECTED')),updated_at=? where id=?",
        runId, runId, runId, runId, Timestamp.from(Instant.now()), runId);
  }

  private ReviewItem view(ItemState state) {
    return new ReviewItem(state.id(), state.runId(), state.variantItemId(), state.variantNo(), state.variantLabel(), state.sequenceNo(),
        state.questionType(), state.typeLabel(), state.difficulty(), state.points(), state.status(), state.question(),
        readMap(state.evidenceJson()), state.review(), state.questionVersion(), state.reviewerId(), state.reviewComment(), state.reviewedAt(),
        state.errorCode(), state.errorMessage());
  }

  private String nextStatus(String current, String decision) {
    return switch (decision) {
      case "APPROVE" -> "APPROVED";
      case "REJECT" -> "REJECTED";
      case "REOPEN", "SAVE", "SAVE_DRAFT" -> "REVIEW_REQUIRED";
      default -> throw new IllegalArgumentException("审核动作无效");
    };
  }

  private String action(String decision, boolean edited) {
    if ("REOPEN".equals(decision)) return "REOPENED";
    if ("APPROVE".equals(decision)) return "APPROVED";
    if ("REJECT".equals(decision)) return "REJECTED";
    return edited ? "EDITED" : "SAVED";
  }

  private String normalizeDecision(String value) {
    String result = clean(value).toUpperCase(Locale.ROOT);
    if (result.isBlank()) result = "SAVE";
    if (!DECISIONS.contains(result)) throw new IllegalArgumentException("审核动作只能是保存、通过、驳回或重新打开");
    return result;
  }

  private boolean isEmpty(Map<String, Object> value) { return value == null || value.isEmpty(); }
  private String text(Object value) { return Objects.toString(value, "").trim(); }
  private String clean(String value) { return Objects.toString(value, "").trim().substring(0, Math.min(2000, Objects.toString(value, "").trim().length())); }
  private String nullIfBlank(String value) { return value == null || value.isBlank() ? null : value; }
  private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException("审核版本保存失败", error); } }
  private Map<String, Object> readMap(String raw) { try { return json.readValue(Objects.toString(raw, "{}"), new TypeReference<>() { }); } catch (Exception ignored) { return Map.of(); } }
  private UUID nullableUuid(Object value) { return value == null ? null : UUID.fromString(String.valueOf(value)); }

  private record ItemState(UUID id, UUID runId, UUID variantItemId, int variantNo, String variantLabel, int sequenceNo,
      String questionType, String typeLabel, String difficulty, int points, String status, Map<String, Object> question,
      String designJson, String evidenceJson, Map<String, Object> review, String errorCode, String errorMessage,
      int questionVersion, UUID reviewerId, String reviewComment, Instant reviewedAt) { }

  public record ReviewRequest(String decision, Integer expectedVersion, String comment, Map<String, Object> question) { }
  public record ReviewItem(UUID id, UUID runId, UUID variantItemId, int variantNo, String variantLabel, int sequenceNo,
      String questionType, String typeLabel, String difficulty, int points, String status, Map<String, Object> question,
      Map<String, Object> evidence, Map<String, Object> review, int questionVersion, UUID reviewerId, String reviewComment,
      Instant reviewedAt, String errorCode, String errorMessage) { }
  public record QuestionVersion(UUID id, int version, Map<String, Object> question, Map<String, Object> design,
      Map<String, Object> evidence, String changeType, String changeSummary, UUID actorId, Instant createdAt) { }
  public record ReviewEvent(UUID id, UUID actorId, String actorName, String action, String fromStatus, String toStatus,
      int questionVersion, String comment, Map<String, Object> snapshot, Instant createdAt) { }
  public record ExportReadiness(UUID runId, int totalCount, int approvedCount, int failedCount, boolean ready,
      List<String> blockers) { }
}
