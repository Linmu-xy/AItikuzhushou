package com.tikuzhushou.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User-owned collections of approved question and paper snapshots. */
@Service
public class UserQuestionBankService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseAccessService access;

  public UserQuestionBankService(JdbcTemplate jdbc, ObjectMapper json, KnowledgeBaseAccessService access) {
    this.jdbc = jdbc; this.json = json; this.access = access;
  }

  public List<BankSummary> list() {
    UUID owner = access.currentUserId();
    List<BankSummary> banks = jdbc.query("select id,name,description,created_at,updated_at from user_question_banks where owner_id=? order by updated_at desc",
        (rs, row) -> new BankSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
            0, 0, instant(rs, "created_at"), instant(rs, "updated_at")), owner);
    Map<UUID, BankCounts> counts = new LinkedHashMap<>();
    jdbc.query("select e.bank_id,e.entry_type,e.source_type,e.source_id,e.source_run_id,e.variant_no,e.snapshot_json " +
        "from user_question_bank_entries e join user_question_banks b on b.id=e.bank_id where b.owner_id=?", rs -> {
      UUID bankId = rs.getObject("bank_id", UUID.class);
      BankCounts value = counts.computeIfAbsent(bankId, ignored -> new BankCounts());
      String sourceType = rs.getString("source_type");
      UUID sourceId = rs.getObject("source_id", UUID.class);
      UUID runId = rs.getObject("source_run_id", UUID.class);
      Integer variantNo = (Integer) rs.getObject("variant_no");
      if ("QUESTION".equals(rs.getString("entry_type"))) {
        value.questionIds.add(sourceType + ":" + sourceId);
        if (runId != null && variantNo != null) value.paperKeys.add(runId + ":" + variantNo);
      }
      else if ("PAPER".equals(rs.getString("entry_type"))) {
        if (runId != null && variantNo != null) value.paperKeys.add(runId + ":" + variantNo);
        for (Object question : listValue(readMap(rs.getString("snapshot_json")).get("questions"))) {
          if (question instanceof Map<?, ?> item && item.get("id") != null) value.questionIds.add(sourceType + ":" + item.get("id"));
        }
      }
    }, owner);
    return banks.stream().map(bank -> {
      BankCounts value = counts.getOrDefault(bank.id(), new BankCounts());
      return new BankSummary(bank.id(), bank.name(), bank.description(), value.questionIds.size(), value.paperKeys.size(), bank.createdAt(), bank.updatedAt());
    }).toList();
  }

  @Transactional
  public BankSummary create(BankInput input) {
    String name = cleanName(input == null ? null : input.name());
    String description = cleanDescription(input == null ? null : input.description());
    UUID id = UUID.randomUUID(); Instant now = Instant.now();
    jdbc.update("insert into user_question_banks(id,owner_id,name,description,created_at,updated_at) values(?,?,?,?,?,?)",
        id, access.currentUserId(), name, description, Timestamp.from(now), Timestamp.from(now));
    return new BankSummary(id, name, description, 0, 0, now, now);
  }

  @Transactional
  public BankSummary update(UUID bankId, BankInput input) {
    requireBank(bankId);
    String name = cleanName(input == null ? null : input.name());
    String description = cleanDescription(input == null ? null : input.description());
    jdbc.update("update user_question_banks set name=?,description=?,updated_at=? where id=? and owner_id=?",
        name, description, Timestamp.from(Instant.now()), bankId, access.currentUserId());
    return list().stream().filter(bank -> bank.id().equals(bankId)).findFirst().orElseThrow();
  }

  @Transactional
  public void delete(UUID bankId) {
    requireBank(bankId);
    jdbc.update("delete from user_question_banks where id=? and owner_id=?", bankId, access.currentUserId());
  }

  public BankDetail detail(UUID bankId) {
    BankSummary summary = list().stream().filter(bank -> bank.id().equals(bankId)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("题库不存在或无权访问"));
    List<Entry> entries = jdbc.query("select id,entry_type,source_type,source_id,source_run_id,variant_no,sequence_no,title,snapshot_json,source_version,created_at " +
        "from user_question_bank_entries where bank_id=? order by entry_type,sequence_no,created_at desc",
        this::mapEntry, bankId);
    return new BankDetail(summary, entries);
  }

  public QuestionBankService.Page availableQuestions(UUID knowledgeBaseId, boolean unlinked,
      java.time.LocalDate from, java.time.LocalDate to, String q, int page, int size) {
    return new QuestionBankService(jdbc, json, access).search(knowledgeBaseId, unlinked, from, to, q, page, size);
  }

  public List<PaperCandidate> availablePapers(String query) {
    String needle = Objects.toString(query, "").trim().toLowerCase(Locale.ROOT);
    if (needle.length() > 200) throw new IllegalArgumentException("搜索关键词不能超过 200 字");
    UUID owner = access.currentUserId();
    Map<String, PaperAccumulator> papers = new LinkedHashMap<>();
    jdbc.query("select r.id run_id,r.project_id,p.name project_name,i.id item_id,i.variant_no,i.variant_label,i.sequence_no,i.question_type,i.type_label,i.difficulty,i.points,i.status,i.question_json,i.question_version " +
        "from exam_project_generation_runs r join exam_projects p on p.id=r.project_id " +
        "join exam_project_generation_items i on i.run_id=r.id where p.owner_id=? and i.status<>'REMOVED' " +
        "order by r.created_at desc,i.variant_no,i.sequence_no", rs -> {
      UUID runId = rs.getObject("run_id", UUID.class);
      int variant = rs.getInt("variant_no");
      String key = runId + ":" + variant;
      PaperAccumulator paper = papers.get(key);
      if (paper == null) {
        paper = new PaperAccumulator(key, runId, rs.getObject("project_id", UUID.class), variant,
            rs.getString("project_name"), rs.getString("variant_label"));
        papers.put(key, paper);
      }
      paper.add(rs);
    }, owner);
    return papers.values().stream().filter(PaperAccumulator::ready)
        .filter(paper -> needle.isBlank() || paper.title.toLowerCase().contains(needle))
        .map(PaperAccumulator::candidate).toList();
  }

  public AvailableContent availableContent(String query) {
    String needle = Objects.toString(query, "").trim().toLowerCase();
    if (needle.length() > 200) throw new IllegalArgumentException("搜索关键词不能超过 200 字");
    UUID owner = access.currentUserId();
    Map<String, PickerPaper> papers = new LinkedHashMap<>();
    jdbc.query("select r.id run_id,i.id item_id,i.variant_no,i.variant_label,i.sequence_no,i.question_type,i.type_label,i.status,i.question_json,p.name project_name " +
        "from exam_project_generation_runs r join exam_projects p on p.id=r.project_id " +
        "join exam_project_generation_items i on i.run_id=r.id where p.owner_id=? and i.status<>'REMOVED' " +
        "order by r.created_at desc,i.variant_no,i.sequence_no", rs -> {
      UUID runId = rs.getObject("run_id", UUID.class);
      int variantNo = rs.getInt("variant_no");
      String key = runId + ":" + variantNo;
      String title = rs.getString("project_name") + " · " + rs.getString("variant_label");
      PickerPaper paper = papers.computeIfAbsent(key, ignored -> new PickerPaper(runId, variantNo, title));
      String status = rs.getString("status");
      paper.totalCount++;
      paper.ready &= List.of("APPROVED", "APPROVED_WITH_RISK").contains(status);
      if (List.of("APPROVED", "APPROVED_WITH_RISK").contains(status)) {
        paper.questions.add(new AvailableQuestion(rs.getObject("item_id", UUID.class), "PROJECT",
            rs.getInt("sequence_no"), rs.getString("question_type"), rs.getString("type_label"), title,
            readMap(rs.getString("question_json"))));
      }
    }, owner);

    List<AvailablePaper> paperResults = papers.values().stream()
        .map(paper -> paper.toAvailable(needle))
        .filter(Objects::nonNull)
        .toList();
    List<AvailableQuestion> standalone = jdbc.query("select r.id,r.sequence_no,r.question_json from question_reviews r " +
        "join generation_jobs j on j.id=r.job_id where r.owner_id=? and j.owner_id=? and r.review_status in ('APPROVED','LOCKED') " +
        "order by r.approved_at desc,r.sequence_no", (rs, row) -> {
          Map<String, Object> question = readMap(rs.getString("question_json"));
          return new AvailableQuestion(rs.getObject("id", UUID.class), "LEGACY_JOB", rs.getInt("sequence_no"),
              Objects.toString(question.get("type"), ""), "单独题目", "历史题目", question);
        }, owner, owner).stream()
        .filter(question -> needle.isBlank() || searchable(question).contains(needle))
        .toList();
    return new AvailableContent(paperResults, standalone);
  }

  private String searchable(AvailableQuestion question) {
    StringBuilder value = new StringBuilder(question.title()).append(' ').append(question.typeLabel());
    for (String key : List.of("stem", "options", "answer", "analysis", "assessmentPoint", "sourceRef", "sourceExcerpt")) {
      value.append(' ').append(Objects.toString(question.question().get(key), ""));
    }
    return value.toString().toLowerCase(Locale.ROOT);
  }

  @Transactional
  public int addQuestions(UUID bankId, AddQuestions request) {
    requireBank(bankId);
    if (request == null || request.questionIds() == null || request.questionIds().isEmpty()) return 0;
    if (request.questionIds().size() > 200) throw new IllegalArgumentException("一次最多添加 200 道题目");
    int added = 0;
    for (QuestionRef ref : request.questionIds().stream().filter(Objects::nonNull).distinct().toList()) {
      String sourceType = Objects.toString(ref.sourceType(), "").toUpperCase();
      UUID questionId = ref.id();
      if (!List.of("PROJECT", "LEGACY_JOB").contains(sourceType) || questionId == null) throw new IllegalArgumentException("题目来源无效");
      SnapshotQuestion question = sourceType.equals("PROJECT") ? approvedProjectQuestion(questionId) : approvedLegacyQuestion(questionId);
      String key = "QUESTION:" + sourceType + ":" + questionId;
      if (exists(bankId, key)) continue;
      insertEntry(bankId, "QUESTION", key, sourceType, question.id(), question.runId(), question.variantNo(),
          question.sequence(), question.title(), question.version(), question.snapshot());
      added++;
    }
    touch(bankId);
    return added;
  }

  @Transactional
  public boolean addPaper(UUID bankId, AddPaper request) {
    requireBank(bankId);
    if (request == null || request.runId() == null || request.variantNo() == null) throw new IllegalArgumentException("请选择一份试卷");
    List<PaperQuestion> questions = new ArrayList<>();
    List<String> rows = jdbc.query("select i.id,i.sequence_no,i.question_type,i.type_label,i.difficulty,i.points,i.status,i.question_json,i.question_version,i.variant_no,i.variant_label,p.name project_name " +
        "from exam_project_generation_items i join exam_project_generation_runs r on r.id=i.run_id " +
        "join exam_projects p on p.id=r.project_id where i.run_id=? and i.variant_no=? and p.owner_id=? and i.status<>'REMOVED' order by i.sequence_no",
        (rs, row) -> {
          String status = rs.getString("status");
          if (!List.of("APPROVED", "APPROVED_WITH_RISK").contains(status)) throw new IllegalArgumentException("这份试卷仍有未审核通过的题目，暂不能整卷加入");
          questions.add(new PaperQuestion(rs.getObject("id", UUID.class), rs.getInt("sequence_no"),
              rs.getString("question_type"), rs.getString("type_label"), rs.getString("difficulty"),
              rs.getInt("points"), status, readMap(rs.getString("question_json")), rs.getInt("question_version"),
              rs.getString("project_name"), rs.getInt("variant_no"), rs.getString("variant_label")));
          return rs.getString("project_name") + " · " + rs.getString("variant_label");
        }, request.runId(), request.variantNo(), access.currentUserId());
    if (rows.isEmpty()) throw new IllegalArgumentException("试卷不存在、无权访问或没有可加入的题目");
    if (rows.size() != questions.size()) throw new IllegalArgumentException("试卷题目读取不完整");
    String title = rows.getFirst();
    String key = "PAPER:PROJECT:" + request.runId() + ":" + request.variantNo();
    boolean changed = false;
    if (!exists(bankId, key)) {
      insertEntry(bankId, "PAPER", key, "PROJECT", request.runId(), request.runId(), request.variantNo(), 0,
          title, 1, write(Map.of("title", title, "questionCount", questions.size())));
      changed = true;
    }
    for (PaperQuestion question : questions) {
      String questionKey = "QUESTION:PROJECT:" + question.id();
      if (exists(bankId, questionKey)) continue;
      Map<String, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("sourceType", "PROJECT"); snapshot.put("sourceId", question.id().toString());
      snapshot.put("runId", request.runId().toString()); snapshot.put("variantNo", question.variantNo());
      snapshot.put("variantLabel", question.variantLabel()); snapshot.put("paperTitle", title);
      snapshot.put("sequence", question.sequence()); snapshot.put("questionType", question.questionType());
      snapshot.put("typeLabel", question.typeLabel()); snapshot.put("difficulty", question.difficulty());
      snapshot.put("points", question.points()); snapshot.put("status", question.status());
      snapshot.put("question", question.question());
      insertEntry(bankId, "QUESTION", questionKey, "PROJECT", question.id(), request.runId(), question.variantNo(),
          question.sequence(), title + " · 第 " + question.sequence() + " 题", question.version(), write(snapshot));
      changed = true;
    }
    touch(bankId);
    return changed;
  }

  @Transactional
  public void removeEntry(UUID bankId, UUID entryId) {
    requireBank(bankId);
    List<EntryIdentity> entries = jdbc.query("select entry_type,source_run_id,variant_no from user_question_bank_entries where id=? and bank_id=?",
        (rs, row) -> new EntryIdentity(rs.getString("entry_type"), rs.getObject("source_run_id", UUID.class), (Integer) rs.getObject("variant_no")), entryId, bankId);
    if (entries.isEmpty()) throw new IllegalArgumentException("题库内容不存在");
    EntryIdentity entry = entries.getFirst();
    if (entry.runId() != null && entry.variantNo() != null && "PAPER".equals(entry.type())) {
      jdbc.update("delete from user_question_bank_entries where bank_id=? and entry_type='QUESTION' and source_type='PROJECT' and source_run_id=? and variant_no=?",
          bankId, entry.runId(), entry.variantNo());
    }
    if (entry.runId() != null && entry.variantNo() != null && "QUESTION".equals(entry.type())) {
      jdbc.update("delete from user_question_bank_entries where bank_id=? and entry_type='PAPER' and source_run_id=? and variant_no=?",
          bankId, entry.runId(), entry.variantNo());
    }
    jdbc.update("delete from user_question_bank_entries where id=? and bank_id=?", entryId, bankId);
    touch(bankId);
  }

  private SnapshotQuestion approvedProjectQuestion(UUID itemId) {
    List<SnapshotQuestion> values = jdbc.query("select i.id,i.run_id,i.sequence_no,i.variant_no,i.variant_label,i.question_type,i.type_label,i.difficulty,i.points,i.status,i.question_json,i.question_version,p.name project_name " +
        "from exam_project_generation_items i join exam_project_generation_runs r on r.id=i.run_id join exam_projects p on p.id=r.project_id " +
        "where i.id=? and p.owner_id=? and i.status in ('APPROVED','APPROVED_WITH_RISK')",
        (rs, row) -> projectSnapshot(rs), itemId, access.currentUserId());
    return values.isEmpty() ? missingApprovedQuestion() : values.getFirst();
  }

  private SnapshotQuestion projectSnapshot(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("id", UUID.class), runId = rs.getObject("run_id", UUID.class);
    int variantNo = rs.getInt("variant_no");
    String paperTitle = rs.getString("project_name") + " · " + rs.getString("variant_label");
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("sourceType", "PROJECT"); value.put("sourceId", id.toString()); value.put("runId", runId.toString());
    value.put("variantNo", variantNo); value.put("variantLabel", rs.getString("variant_label")); value.put("paperTitle", paperTitle);
    value.put("sequence", rs.getInt("sequence_no")); value.put("questionType", rs.getString("question_type"));
    value.put("typeLabel", rs.getString("type_label")); value.put("difficulty", rs.getString("difficulty"));
    value.put("points", rs.getInt("points")); value.put("status", rs.getString("status"));
    value.put("question", readMap(rs.getString("question_json")));
    return new SnapshotQuestion(id, runId, variantNo, rs.getInt("sequence_no"), paperTitle + " · 第 " + rs.getInt("sequence_no") + " 题",
        rs.getInt("question_version"), write(value));
  }

  private SnapshotQuestion approvedLegacyQuestion(UUID reviewId) {
    List<SnapshotQuestion> values = jdbc.query("select r.id,r.job_id,r.sequence_no,r.review_status,r.question_json,r.version,j.owner_id,j.request_json " +
        "from question_reviews r join generation_jobs j on j.id=r.job_id where r.id=? and r.owner_id=? and j.owner_id=? and r.review_status in ('APPROVED','LOCKED')",
        (rs, row) -> {
          UUID id = rs.getObject("id", UUID.class), jobId = rs.getObject("job_id", UUID.class);
          Map<String, Object> snapshot = new LinkedHashMap<>(); snapshot.put("sourceType", "LEGACY_JOB");
          snapshot.put("sourceId", id.toString()); snapshot.put("jobId", jobId.toString());
          snapshot.put("sequence", rs.getInt("sequence_no")); snapshot.put("status", rs.getString("review_status"));
          snapshot.put("question", readMap(rs.getString("question_json")));
          return new SnapshotQuestion(id, null, null, rs.getInt("sequence_no"), "单独添加的题目 · 第 " + rs.getInt("sequence_no") + " 题",
              rs.getInt("version"), write(snapshot));
        }, reviewId, access.currentUserId(), access.currentUserId());
    if (values.isEmpty()) return missingApprovedQuestion();
    return values.getFirst();
  }

  private SnapshotQuestion missingApprovedQuestion() { throw new IllegalArgumentException("题目不存在、无权访问或尚未审核通过"); }
  private void insertEntry(UUID bankId, String type, String key, String sourceType, UUID sourceId, UUID runId,
      Integer variantNo, int sequence, String title, int version, String snapshot) {
    jdbc.update("insert into user_question_bank_entries(id,bank_id,entry_type,entry_key,source_type,source_id,source_run_id,variant_no,sequence_no,title,snapshot_json,source_version,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(), bankId, type, key, sourceType, sourceId, runId, variantNo, sequence, title, snapshot, version, Timestamp.from(Instant.now()));
  }
  private boolean exists(UUID bankId, String key) { return jdbc.queryForObject("select count(*) from user_question_bank_entries where bank_id=? and entry_key=?", Integer.class, bankId, key) > 0; }
  private void touch(UUID bankId) { jdbc.update("update user_question_banks set updated_at=? where id=? and owner_id=?", Timestamp.from(Instant.now()), bankId, access.currentUserId()); }
  private void requireBank(UUID id) {
    Integer count = jdbc.queryForObject("select count(*) from user_question_banks where id=? and owner_id=?", Integer.class, id, access.currentUserId());
    if (count == null || count == 0) throw new AccessDeniedException("题库不存在或无权访问");
  }
  private String cleanName(String value) { String name = Objects.toString(value, "").trim(); if (name.isBlank() || name.length() > 100) throw new IllegalArgumentException("题库名称需为 1–100 个字符"); return name; }
  private String cleanDescription(String value) { String description = Objects.toString(value, "").trim(); if (description.length() > 500) throw new IllegalArgumentException("描述不能超过 500 个字符"); return description; }
  private Instant instant(ResultSet rs, String key) throws SQLException { return rs.getTimestamp(key).toInstant(); }
  private Entry mapEntry(ResultSet rs, int row) throws SQLException {
    return new Entry(rs.getObject("id", UUID.class), rs.getString("entry_type"), rs.getString("source_type"),
        rs.getObject("source_id", UUID.class), rs.getObject("source_run_id", UUID.class),
        (Integer) rs.getObject("variant_no"), rs.getInt("sequence_no"), rs.getString("title"),
        readMap(rs.getString("snapshot_json")), rs.getInt("source_version"), instant(rs, "created_at"));
  }
  private Map<String, Object> readMap(String raw) { try { Map<String, Object> value = json.readValue(Objects.toString(raw, "{}"), new TypeReference<>() {}); return value == null ? Map.of() : value; } catch (Exception ignored) { return Map.of(); } }
  private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException("题库内容保存失败", error); } }

  private final class PaperAccumulator {
    final String key; final UUID runId; final UUID projectId; final int variant; final String project; final String label;
    final List<Map<String, Object>> questions = new ArrayList<>(); boolean ready = true; String title;
    PaperAccumulator(String key, UUID runId, UUID projectId, int variant, String project, String label) {
      this.key=key; this.runId=runId; this.projectId=projectId; this.variant=variant; this.project=project; this.label=label;
      title = project + " · " + label;
    }
    void add(ResultSet rs) throws SQLException {
      String status = rs.getString("status"); ready &= List.of("APPROVED", "APPROVED_WITH_RISK").contains(status);
      Map<String, Object> q = new LinkedHashMap<>(); q.put("id", rs.getObject("item_id", UUID.class).toString());
      q.put("sequence", rs.getInt("sequence_no")); q.put("questionType", rs.getString("question_type"));
      q.put("typeLabel", rs.getString("type_label")); q.put("difficulty", rs.getString("difficulty"));
      q.put("points", rs.getInt("points")); q.put("status", status); q.put("version", rs.getInt("question_version"));
      q.put("question", readMap(rs.getString("question_json"))); questions.add(q);
    }
    boolean ready() { return ready && !questions.isEmpty(); }
    PaperCandidate candidate() { return new PaperCandidate(runId, variant, title, questions.size(), questions.stream().map(q -> Objects.toString(q.get("typeLabel"), "题目")).distinct().toList()); }
  }
  private final class PickerPaper {
    final UUID runId;
    final int variantNo;
    final String title;
    final List<AvailableQuestion> questions = new ArrayList<>();
    int totalCount;
    boolean ready = true;
    PickerPaper(UUID runId, int variantNo, String title) { this.runId = runId; this.variantNo = variantNo; this.title = title; }
    AvailablePaper toAvailable(String needle) {
      List<AvailableQuestion> matches = questions.stream()
          .filter(question -> needle.isBlank() || title.toLowerCase(Locale.ROOT).contains(needle) || searchable(question).contains(needle))
          .toList();
      if (!needle.isBlank() && !title.toLowerCase(Locale.ROOT).contains(needle) && matches.isEmpty()) return null;
      return new AvailablePaper(runId, variantNo, title, totalCount, questions.size(), ready, matches);
    }
  }
  private List<?> listValue(Object value) { return value instanceof List<?> list ? list : List.of(); }
  private record SnapshotQuestion(UUID id, UUID runId, Integer variantNo, int sequence, String title, int version, String snapshot) { }
  private record PaperQuestion(UUID id, int sequence, String questionType, String typeLabel, String difficulty,
      int points, String status, Map<String, Object> question, int version, String projectName,
      int variantNo, String variantLabel) { }
  private record EntryIdentity(String type, UUID runId, Integer variantNo) { }
  private static final class BankCounts {
    final java.util.Set<String> questionIds = new java.util.HashSet<>();
    final java.util.Set<String> paperKeys = new java.util.HashSet<>();
  }
  public record BankInput(String name, String description) { }
  public record QuestionRef(String sourceType, UUID id) { }
  public record AddQuestions(List<QuestionRef> questionIds) { }
  public record AddPaper(UUID runId, Integer variantNo) { }
  public record BankSummary(UUID id, String name, String description, long questionCount, long paperCount, Instant createdAt, Instant updatedAt) { }
  public record BankDetail(BankSummary bank, List<Entry> entries) { }
  public record Entry(UUID id, String entryType, String sourceType, UUID sourceId, UUID sourceRunId, Integer variantNo, int sequence, String title, Map<String, Object> snapshot, int sourceVersion, Instant addedAt) { }
  public record PaperCandidate(UUID runId, int variantNo, String title, int questionCount, List<String> types) { }
  public record AvailableContent(List<AvailablePaper> papers, List<AvailableQuestion> standalone) { }
  public record AvailablePaper(UUID runId, int variantNo, String title, int totalQuestionCount,
      int selectableQuestionCount, boolean wholePaperReady, List<AvailableQuestion> questions) { }
  public record AvailableQuestion(UUID id, String sourceType, int sequence, String questionType,
      String typeLabel, String title, Map<String, Object> question) { }
}
