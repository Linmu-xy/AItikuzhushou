package com.tikuzhushou.fast;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.core.CoreBusinessService;
import com.tikuzhushou.document.DocumentIntakeService;
import com.tikuzhushou.document.DocumentParsingService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import com.tikuzhushou.project.QuestionQualityChecks;
import com.tikuzhushou.question.QuestionRefinementService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Opt-in document-to-question-bank flow. It deliberately uses the existing FAST question
 * generator and keeps the project/V2/Pro pipelines untouched.
 */
@Service
public class FastDocumentGenerationService {
  private static final Set<String> TYPES = Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE",
      "FILL_BLANK", "SHORT_ANSWER", "CALCULATION", "ESSAY", "CASE_ANALYSIS", "COMPREHENSIVE");
  private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseAccessService access;
  private final DocumentIntakeService intake;
  private final DocumentParsingService parsing;
  private final QuestionRefinementService refiner;
  private final CoreBusinessService core;
  private final boolean enabled;
  private final int maxCharacters;
  private final int segmentTargetCharacters;
  private final int maxQuestions;
  private final int maxSegments;

  public FastDocumentGenerationService(JdbcTemplate jdbc, ObjectMapper json, KnowledgeBaseAccessService access,
      DocumentIntakeService intake, DocumentParsingService parsing, QuestionRefinementService refiner,
      CoreBusinessService core, @Value("${app.fast-document.enabled:false}") boolean enabled,
      @Value("${app.fast-document.max-characters:200000}") int maxCharacters,
      @Value("${app.fast-document.segment-target-characters:1000}") int segmentTargetCharacters,
      @Value("${app.fast-document.max-questions:2500}") int maxQuestions,
      @Value("${app.fast-document.max-segments:400}") int maxSegments) {
    this.jdbc = jdbc; this.json = json; this.access = access; this.intake = intake; this.parsing = parsing;
    this.refiner = refiner; this.core = core; this.enabled = enabled;
    this.maxCharacters = Math.max(1_000, Math.min(maxCharacters, 1_000_000));
    this.segmentTargetCharacters = Math.max(400, Math.min(segmentTargetCharacters, 4_000));
    this.maxQuestions = Math.max(1, Math.min(maxQuestions, 10_000));
    this.maxSegments = Math.max(1, Math.min(maxSegments, 2_000));
  }

  public JobView create(CreateRequest request) {
    ensureEnabled();
    if (request == null || request.documentId() == null) throw new IllegalArgumentException("请选择要出题的文档");
    DocumentIntakeService.DocumentReceipt document = intake.get(request.documentId());
    List<String> types = normalizeTypes(request.questionTypes());
    String density = normalizeDensity(request.density());
    String difficulty = normalizeDifficulty(request.difficulty());
    String key = normalizeKey(request.idempotencyKey());
    UUID owner = access.currentUserId();
    if (!key.isBlank()) {
      List<UUID> existing = jdbc.query("select id from fast_generation_jobs where owner_id=? and idempotency_key=?",
          (rs, row) -> rs.getObject(1, UUID.class), owner, key);
      if (!existing.isEmpty()) return get(existing.getFirst());
    }
    UUID id = UUID.randomUUID(); Instant now = Instant.now();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("documentId", document.id()); payload.put("documentName", document.name());
    payload.put("density", density); payload.put("difficulty", difficulty); payload.put("questionTypes", types);
    payload.put("requiresManualReview", true); payload.put("pipeline", "FAST_DOCUMENT_V1");
    try {
      jdbc.update("insert into fast_generation_jobs(id,owner_id,document_id,status,density,question_types_json,request_json,idempotency_key,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?)",
          id, owner, document.id(), "QUEUED", density, write(types), write(payload), key.isBlank() ? null : key,
          Timestamp.from(now), Timestamp.from(now));
    } catch (DuplicateKeyException race) {
      if (!key.isBlank()) return get(jdbc.queryForObject("select id from fast_generation_jobs where owner_id=? and idempotency_key=?", UUID.class, owner, key));
      throw race;
    }
    return get(id);
  }

  public void execute(UUID jobId) {
    JobRow job = raw(jobId);
    if (terminal(job.status())) return;
    updateJob(jobId, "RUNNING", 0, 0, 0, "正在准备文档解析", null, null, Instant.now(), null);
    try {
      DocumentParsingService.ParsedDocument document = parsing.get(job.documentId());
      if (!"PARSED".equals(document.status())) document = parsing.parse(job.documentId(), (stage, progress, processed, total, message) ->
          updateJob(jobId, "RUNNING", Math.min(35, Math.max(1, progress / 3)), 0, 0, message, null, null, null, null));
      if (!"PARSED".equals(document.status())) throw new IllegalStateException("文档解析质量不足，快速出题已停止：" + String.join("；", document.warnings()));
      if (document.fullText() == null || document.fullText().replaceAll("\\s", "").length() < 20)
        throw new IllegalStateException("文档没有足够的可出题正文");
      if (document.fullText().length() > maxCharacters)
        throw new IllegalStateException("文档超过快速模式单次处理上限 " + maxCharacters + " 字，请拆分后分别提交");
      List<Segment> segments = segment(document.chunks());
      if (segments.isEmpty()) throw new IllegalStateException("文档没有可用的逻辑段落");
      if (segments.size() > maxSegments) throw new IllegalStateException("文档逻辑段落过多，请拆分文档后再试");
      prepareSegments(job, segments);
      updateJob(jobId, "RUNNING", 38, 0, segments.size(), "正在按段生成题目", null, null, null, null);
      generate(job, segments);
      finish(jobId);
    } catch (Exception error) {
      String message = limit(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), 2000);
      updateJob(jobId, "FAILED", 100, processedSegments(jobId), totalSegments(jobId), "快速出题失败", "FAST_GENERATION_FAILED", message, Instant.now(), Instant.now());
      throw error instanceof RuntimeException runtime ? runtime : new IllegalStateException(message, error);
    }
  }

  public JobView get(UUID id) { JobRow row = raw(id); assertOwner(row.ownerId()); return view(row); }

  public List<ItemView> items(UUID id) {
    JobRow row = raw(id); assertOwner(row.ownerId());
    return jdbc.query("select id,segment_id,sequence_no,question_type,difficulty,question_json,review_json,status,attempts,import_status,error_code,error_message from fast_generation_items where job_id=? order by sequence_no",
        (rs, n) -> new ItemView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4),
            rs.getString(5), readMap(rs.getString(6)), readMap(rs.getString(7)), rs.getString(8), rs.getInt(9),
            rs.getString(10), rs.getString(11), rs.getString(12)), id);
  }

  public JobView retry(UUID id) {
    ensureEnabled(); JobRow row = raw(id); assertOwner(row.ownerId());
    if (!Set.of("FAILED", "PARTIAL_SUCCESS").contains(row.status())) throw new IllegalArgumentException("当前任务没有可重试的失败项");
    jdbc.update("update fast_generation_segments set status='PLANNED',error_message=null,updated_at=? where job_id=? and status='FAILED'",
        Timestamp.from(Instant.now()), id);
    jdbc.update("update fast_generation_items set status='PLANNED',error_code=null,error_message=null,updated_at=? where job_id=? and status='FAILED'",
        Timestamp.from(Instant.now()), id);
    jdbc.update("update fast_generation_jobs set status='QUEUED',error_message=null,finished_at=null,updated_at=? where id=?",
        Timestamp.from(Instant.now()), id);
    return get(id);
  }

  public JobView cancel(UUID id) {
    JobRow row = raw(id); assertOwner(row.ownerId());
    if (terminal(row.status())) throw new IllegalArgumentException("已结束的任务不能取消");
    jdbc.update("update fast_generation_jobs set status='CANCELLED',finished_at=?,updated_at=? where id=? and status in ('QUEUED','RUNNING')",
        Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
    return get(id);
  }

  public CoreBusinessService.Job importToQuestionBank(UUID id) {
    JobRow row = raw(id); assertOwner(row.ownerId());
    if (!Set.of("READY_FOR_REVIEW", "PARTIAL_SUCCESS").contains(row.status())) throw new IllegalArgumentException("任务尚未生成可导入题目");
    List<Map<String, Object>> questions = jdbc.query("select question_json from fast_generation_items where job_id=? and status='REVIEW_REQUIRED' and import_status='NOT_IMPORTED' order by sequence_no",
        (rs, n) -> readMap(rs.getString(1)), id);
    if (questions.isEmpty()) throw new IllegalArgumentException("没有可导入的新题目");
    List<Map<String, Object>> normalized = questions.stream().map(this::importShape).toList();
    CoreBusinessService.Job imported = core.importQuestions(normalized);
    jdbc.update("update fast_generation_items set import_status='IMPORTED',updated_at=? where job_id=? and status='REVIEW_REQUIRED' and import_status='NOT_IMPORTED'",
        Timestamp.from(Instant.now()), id);
    return imported;
  }

  private void generate(JobRow job, List<Segment> segments) {
    List<Map<String, Object>> itemRows = jdbc.query("select id,segment_id,sequence_no,question_type,difficulty from fast_generation_items where job_id=? order by sequence_no",
        (rs, n) -> Map.of("id", rs.getObject(1, UUID.class), "segmentId", rs.getObject(2, UUID.class),
            "sequence", rs.getInt(3), "type", rs.getString(4), "difficulty", rs.getString(5)), job.id());
    Map<UUID, Segment> byId = new LinkedHashMap<>(); segments.forEach(value -> byId.put(value.id(), value));
    int processed = 0;
    for (Segment segment : segments) {
      if ("CANCELLED".equals(raw(job.id()).status())) throw new IllegalStateException("任务已取消");
      jdbc.update("update fast_generation_segments set status='RUNNING',attempts=attempts+1,updated_at=? where id=?",
          Timestamp.from(Instant.now()), segment.id());
      List<Map<String, Object>> rows = itemRows.stream().filter(row -> segment.id().equals(row.get("segmentId"))).toList();
      int segmentGenerated = 0;
      for (int start = 0; start < rows.size(); start += Math.max(1, refiner.batchSize("FAST"))) {
        List<Map<String, Object>> batch = rows.subList(start, Math.min(rows.size(), start + Math.max(1, refiner.batchSize("FAST"))));
        List<Map<String, Object>> drafts = batch.stream().map(row -> draft((int) row.get("sequence"), text(row.get("type")),
            text(row.get("difficulty")), segment)).toList();
        List<QuestionRefinementService.Candidate> candidates = refiner.generateCandidates(drafts, "FAST");
        for (int index = 0; index < batch.size(); index++) {
          Map<String, Object> row = batch.get(index);
          QuestionRefinementService.Candidate candidate = index < candidates.size() ? candidates.get(index) : null;
          Map<String, Object> question = candidate == null ? Map.of() : candidate.question();
          List<QuestionQualityChecks.Issue> issues = candidate == null || !candidate.valid()
              ? List.of(new QuestionQualityChecks.Issue("GENERATION", "ERROR", candidate == null ? "模型未返回题目" : Objects.toString(candidate.failureReason(), "基础校验未通过")))
              : QuestionQualityChecks.inspect(question, text(row.get("type")), 2);
          boolean valid = candidate != null && candidate.valid() && issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()));
          Map<String, Object> review = new LinkedHashMap<>(); review.put("manualReviewRequired", true);
          review.put("issues", issues); review.put("sourceRef", segment.sourceRef());
          updateItem(UUID.fromString(row.get("id").toString()), valid ? "REVIEW_REQUIRED" : "FAILED", question, review,
              valid ? null : "FAST_BASIC_REVIEW_FAILED", valid ? null : issues.stream().map(QuestionQualityChecks.Issue::message).findFirst().orElse("质量检查失败"));
          if (valid) segmentGenerated++;
        }
      }
      jdbc.update("update fast_generation_segments set status=?,generated_questions=?,error_message=?,updated_at=? where id=?",
          segmentGenerated == rows.size() ? "SUCCEEDED" : "FAILED", segmentGenerated,
          segmentGenerated == rows.size() ? null : "部分题目未通过基础结构检查", Timestamp.from(Instant.now()), segment.id());
      processed++;
      updateCounts(job.id(), processed, segments.size());
    }
  }

  private Map<String, Object> draft(int sequence, String type, String difficulty, Segment segment) {
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("sequence", sequence); draft.put("type", type); draft.put("difficulty", difficulty); draft.put("points", 2);
    draft.put("level", "KNOWLEDGE_BASE"); draft.put("basicReviewOnly", true);
    draft.put("cognitiveTarget", "EASY".equals(difficulty) ? "RECOGNIZE" : "MEDIUM".equals(difficulty) ? "APPLY" : "ANALYZE_DECIDE");
    draft.put("abilityObjective", "理解并应用本资料段落中的核心知识");
    draft.put("assessmentPoint", "文档重点：" + compact(segment.content(), 180));
    draft.put("assessmentTask", "根据资料内容完成" + typeLabel(type) + "，不得引入资料外的决定性事实");
    draft.put("procedureEvidence", segment.content()); draft.put("mappingEvidence", List.of(segment.sourceRef()));
    draft.put("sourceRef", segment.sourceRef()); draft.put("sourceExcerpt", segment.content());
    draft.put("evidencePack", Map.of("standardEvidence", segment.content(), "contextAssets", List.of()));
    draft.put("setGuidance", "快速文档建库：题目必须围绕当前资料段落，生成后仍需人工审核");
    draft.put("recentQuestionSummaries", List.of());
    return draft;
  }

  private void prepareSegments(JobRow job, List<Segment> segments) {
    Integer existing = jdbc.queryForObject("select count(*) from fast_generation_segments where job_id=?", Integer.class, job.id());
    if (existing != null && existing > 0) return;
    int sequence = 1; int questionSequence = 1; int totalQuestions = 0;
    int perType = "HIGH".equals(job.density()) ? 10 : 5;
    List<String> types = readTypes(job.questionTypesJson());
    for (Segment original : segments) {
      int planned = Math.min(maxQuestions - totalQuestions, perType * types.size());
      if (planned <= 0) break;
      UUID segmentId = UUID.randomUUID(); Instant now = Instant.now();
      jdbc.update("insert into fast_generation_segments(id,job_id,sequence_no,source_ref,source_excerpt,content_hash,planned_questions,created_at,updated_at) values(?,?,?,?,?,?,?,?,?)",
          original.id(), job.id(), sequence++, original.sourceRef(), original.content(), hash(original.content()), planned,
          Timestamp.from(now), Timestamp.from(now));
      int created = 0;
      for (String type : types) {
        for (int copy = 0; copy < perType && created < planned; copy++) {
          UUID itemId = UUID.randomUUID();
          jdbc.update("insert into fast_generation_items(id,job_id,segment_id,sequence_no,question_type,difficulty,question_json,status,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?)",
              itemId, job.id(), original.id(), questionSequence++, type, difficultyFor(type, job), "{}", "PLANNED", Timestamp.from(now), Timestamp.from(now));
          created++;
        }
      }
      totalQuestions += planned;
    }
    if (totalQuestions == 0) throw new IllegalStateException("未能根据题型和密度生成题位");
    jdbc.update("update fast_generation_jobs set total_segments=?,requested_questions=?,updated_at=? where id=?",
        sequence - 1, totalQuestions, Timestamp.from(Instant.now()), job.id());
  }

  private List<Segment> segment(List<DocumentParsingService.Chunk> chunks) {
    List<Segment> result = new ArrayList<>();
    for (DocumentParsingService.Chunk chunk : chunks) {
      String content = Objects.toString(chunk.content(), "").trim(); if (content.isBlank()) continue;
      String remaining = content;
      while (!remaining.isBlank()) {
        int cut = Math.min(segmentTargetCharacters, remaining.length());
        if (cut < remaining.length()) {
          int boundary = Math.max(remaining.lastIndexOf('\n', cut), remaining.lastIndexOf('。', cut));
          if (boundary >= segmentTargetCharacters / 2) cut = boundary + 1;
        }
        String piece = remaining.substring(0, cut).trim(); remaining = remaining.substring(cut).trim();
        if (!piece.isBlank()) result.add(new Segment(UUID.randomUUID(), chunk.sourceRef(), piece, 0));
      }
    }
    return result;
  }

  private void finish(UUID id) {
    Integer failed = jdbc.queryForObject("select count(*) from fast_generation_items where job_id=? and status='FAILED'", Integer.class, id);
    Integer generated = jdbc.queryForObject("select count(*) from fast_generation_items where job_id=? and status='REVIEW_REQUIRED'", Integer.class, id);
    String status = generated != null && generated > 0 ? (failed != null && failed > 0 ? "PARTIAL_SUCCESS" : "READY_FOR_REVIEW") : "FAILED";
    jdbc.update("update fast_generation_jobs set status=?,generated_questions=?,failed_questions=?,progress=?,finished_at=?,updated_at=? where id=?",
        status, generated == null ? 0 : generated, failed == null ? 0 : failed, 100, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
  }

  private void updateCounts(UUID id, int processed, int totalSegments) {
    int generated = jdbc.queryForObject("select count(*) from fast_generation_items where job_id=? and status='REVIEW_REQUIRED'", Integer.class, id);
    int failed = jdbc.queryForObject("select count(*) from fast_generation_items where job_id=? and status='FAILED'", Integer.class, id);
    int progress = Math.min(99, 38 + (int) Math.round(60d * processed / Math.max(1, totalSegments)));
    jdbc.update("update fast_generation_jobs set progress=?,processed_segments=?,total_segments=?,generated_questions=?,failed_questions=?,updated_at=? where id=?",
        progress, processed, totalSegments, generated, failed, Timestamp.from(Instant.now()), id);
  }

  private void updateItem(UUID id, String status, Map<String, Object> question, Map<String, Object> review,
      String errorCode, String errorMessage) {
    jdbc.update("update fast_generation_items set status=?,question_json=?,review_json=?,error_code=?,error_message=?,attempts=attempts+1,updated_at=? where id=?",
        status, write(question), write(review), errorCode, errorMessage, Timestamp.from(Instant.now()), id);
  }

  private void updateJob(UUID id, String status, int progress, int processed, int total, String message,
      String errorCode, String errorMessage, Instant startedAt, Instant finishedAt) {
    // Progress is derived from processed/total segments in view(); do not overload
    // requested_questions with transient progress values during parsing.
    jdbc.update("update fast_generation_jobs set status=?,processed_segments=?,total_segments=case when ?=0 then total_segments else ? end,error_message=?,updated_at=?,started_at=coalesce(started_at,?),finished_at=? where id=?",
        status, processed, total, total, errorMessage, Timestamp.from(Instant.now()),
        startedAt == null ? Timestamp.from(Instant.now()) : Timestamp.from(startedAt), finishedAt == null ? null : Timestamp.from(finishedAt), id);
  }

  private JobRow raw(UUID id) {
    List<JobRow> rows = jdbc.query("select id,owner_id,document_id,status,density,question_types_json,request_json,requested_questions,generated_questions,failed_questions,processed_segments,total_segments,error_message,created_at,started_at,finished_at,updated_at from fast_generation_jobs where id=?",
        (rs, n) -> new JobRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12),
            rs.getString(13), rs.getTimestamp(14).toInstant(), instant(rs.getTimestamp(15)), instant(rs.getTimestamp(16)), rs.getTimestamp(17).toInstant()), id);
    if (rows.isEmpty()) throw new IllegalArgumentException("快速出题任务不存在");
    return rows.getFirst();
  }

  private JobView view(JobRow row) {
    int progress = "READY_FOR_REVIEW".equals(row.status()) || "PARTIAL_SUCCESS".equals(row.status()) || "FAILED".equals(row.status()) ? 100 :
        row.totalSegments() == 0 ? 0 : Math.min(99, 38 + (int) Math.round(60d * row.processedSegments() / row.totalSegments()));
    return new JobView(row.id(), row.documentId(), row.status(), progress, row.density(), readTypes(row.questionTypesJson()),
        row.requestedQuestions(), row.generatedQuestions(), row.failedQuestions(), row.processedSegments(), row.totalSegments(),
        row.errorMessage(), row.createdAt(), row.startedAt(), row.finishedAt());
  }

  private void assertOwner(UUID ownerId) {
    if (!access.admin() && !ownerId.equals(access.currentUserId())) throw new AccessDeniedException("无权访问其他用户的快速出题任务");
  }

  private void ensureEnabled() { if (!enabled) throw new IllegalStateException("FAST_DOCUMENT_DISABLED：快速文档出题功能尚未启用"); }
  private boolean terminal(String status) { return Set.of("READY_FOR_REVIEW", "PARTIAL_SUCCESS", "FAILED", "CANCELLED").contains(status); }
  private String normalizeDensity(String value) { String result = Objects.toString(value, "LOW").trim().toUpperCase(Locale.ROOT); if (!Set.of("LOW", "HIGH").contains(result)) throw new IllegalArgumentException("密度只能是 LOW 或 HIGH"); return result; }
  private String normalizeDifficulty(String value) { String result = Objects.toString(value, "EASY").trim().toUpperCase(Locale.ROOT); if (!DIFFICULTIES.contains(result)) throw new IllegalArgumentException("难度只能是 EASY、MEDIUM 或 HARD"); return result; }
  private List<String> normalizeTypes(List<String> values) {
    if (values == null || values.isEmpty()) throw new IllegalArgumentException("至少选择一种题型");
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String value : values) { String type = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT); if (!TYPES.contains(type)) throw new IllegalArgumentException("不支持的题型：" + type); result.add(type); }
    return List.copyOf(result);
  }
  private String normalizeKey(String value) { String key = Objects.toString(value, "").trim(); if (key.isBlank()) return ""; if (!key.matches("[A-Za-z0-9._:-]{8,120}")) throw new IllegalArgumentException("幂等键格式无效"); return key; }
  private List<String> readTypes(String value) { try { return json.readValue(value, new TypeReference<>() { }); } catch (Exception error) { return List.of(); } }
  private Map<String, Object> readMap(String value) { try { return json.readValue(Objects.toString(value, "{}"), new TypeReference<>() { }); } catch (Exception error) { return Map.of(); } }
  private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException("任务数据序列化失败", error); } }
  private String hash(String value) { try { return HexFormatHolder.format(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception error) { throw new IllegalStateException(error); } }
  private String compact(String value, int max) { String safe = Objects.toString(value, "").trim(); return safe.length() <= max ? safe : safe.substring(0, max) + "…"; }
  private String limit(String value, int max) { return value == null ? null : value.substring(0, Math.min(max, value.length())); }
  private String text(Object value) { return Objects.toString(value, ""); }
  private String difficultyFor(String type, JobRow job) {
    String configured = text(readMap(job.requestJson()).get("difficulty")).toUpperCase(Locale.ROOT);
    if (DIFFICULTIES.contains(configured)) return configured;
    return Set.of("CALCULATION", "CASE_ANALYSIS", "COMPREHENSIVE", "ESSAY").contains(type) ? "MEDIUM" : "EASY";
  }
  private String typeLabel(String type) { return Map.of("SINGLE_CHOICE", "单选题", "MULTIPLE_CHOICE", "多选题", "TRUE_FALSE", "判断题", "FILL_BLANK", "填空题", "SHORT_ANSWER", "简答题", "CALCULATION", "计算题", "ESSAY", "论述题", "CASE_ANALYSIS", "案例题", "COMPREHENSIVE", "综合题").getOrDefault(type, "题目"); }
  private int processedSegments(UUID id) { return Objects.requireNonNullElse(jdbc.queryForObject("select processed_segments from fast_generation_jobs where id=?", Integer.class, id), 0); }
  private int totalSegments(UUID id) { return Objects.requireNonNullElse(jdbc.queryForObject("select total_segments from fast_generation_jobs where id=?", Integer.class, id), 0); }
  private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
  private Map<String, Object> importShape(Map<String, Object> question) {
    Map<String, Object> value = new LinkedHashMap<>(question);
    value.putIfAbsent("assessmentPoint", "快速文档出题"); value.putIfAbsent("difficulty", "EASY");
    value.putIfAbsent("sourceRef", ""); value.putIfAbsent("sourceExcerpt", "");
    return value;
  }

  private record Segment(UUID id, String sourceRef, String content, int plannedQuestions) { }
  private record JobRow(UUID id, UUID ownerId, UUID documentId, String status, String density, String questionTypesJson,
      String requestJson, int requestedQuestions, int generatedQuestions, int failedQuestions, int processedSegments,
      int totalSegments, String errorMessage, Instant createdAt, Instant startedAt, Instant finishedAt, Instant updatedAt) { }
  public record CreateRequest(UUID documentId, String density, String difficulty, List<String> questionTypes, String idempotencyKey) { }
  public record JobView(UUID id, UUID documentId, String status, int progress, String density, List<String> questionTypes,
      int requestedQuestions, int generatedQuestions, int failedQuestions, int processedSegments, int totalSegments,
      String errorMessage, Instant createdAt, Instant startedAt, Instant finishedAt) { }
  public record ItemView(UUID id, UUID segmentId, int sequence, String type, String difficulty, Map<String, Object> question,
      Map<String, Object> review, String status, int attempts, String importStatus, String errorCode, String errorMessage) { }
  private static final class HexFormatHolder { static String format(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); } }
}
