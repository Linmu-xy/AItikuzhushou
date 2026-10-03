package com.tikuzhushou.fast;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.core.CoreBusinessService;
import com.tikuzhushou.document.ObjectStorageService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import com.tikuzhushou.project.QuestionQualityChecks;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Converts common external Word/Excel question files to the existing import schema. */
@Service
public class QuestionNormalizationService {
  private static final Pattern QUESTION = Pattern.compile("^\\s*(\\d+)\\s*[.、．)）:]\\s*(.*)$");
  private static final Pattern OPTION = Pattern.compile("^\\s*([A-DＡ-Ｄ])\\s*[.、．:：)）]\\s*(.*)$");
  private static final Set<String> TYPES = Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "FILL_BLANK",
      "SHORT_ANSWER", "CALCULATION", "ESSAY", "CASE_ANALYSIS", "COMPREHENSIVE");
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseAccessService access;
  private final ObjectStorageService storage;
  private final CoreBusinessService core;
  private final boolean enabled;

  public QuestionNormalizationService(JdbcTemplate jdbc, ObjectMapper json, KnowledgeBaseAccessService access,
      ObjectStorageService storage, CoreBusinessService core,
      @Value("${app.question-normalization.enabled:false}") boolean enabled) {
    this.jdbc = jdbc; this.json = json; this.access = access; this.storage = storage; this.core = core; this.enabled = enabled;
  }

  public JobView create(MultipartFile file) throws Exception {
    ensureEnabled();
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择 Word 或 Excel 题库文件");
    if (file.getSize() > 20L * 1024 * 1024) throw new IllegalArgumentException("题库文件不能超过 20MB");
    String name = safeName(Objects.requireNonNullElse(file.getOriginalFilename(), "questions"));
    String lower = name.toLowerCase(Locale.ROOT);
    if (!(lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".xls") || lower.endsWith(".xlsx")))
      throw new IllegalArgumentException("仅支持 Word 或 Excel 题库文件");
    UUID id = UUID.randomUUID(); String key = "question-normalization/" + id + "_" + name;
    String stored;
    try (InputStream input = file.getInputStream()) { stored = storage.put(key, input, file.getSize(), mediaType(lower)); }
    catch (Exception error) { throw new IllegalStateException("题库原文件保存失败", error); }
    Instant now = Instant.now();
    jdbc.update("insert into question_normalization_jobs(id,owner_id,original_name,media_type,source_storage_key,status,created_at,updated_at) values(?,?,?,?,?,?,?,?)",
        id, access.currentUserId(), name, mediaType(lower), stored, "READY_FOR_REVIEW", Timestamp.from(now), Timestamp.from(now));
    try {
      Path materialized = storage.materialize(stored, suffix(lower));
      try {
        List<Map<String, Object>> questions = lower.endsWith(".xls") || lower.endsWith(".xlsx") ? parseExcel(materialized) : parseWord(materialized, lower.endsWith(".docx"));
        persistItems(id, questions);
      } finally { storage.cleanupMaterialized(materialized); }
      return get(id);
    } catch (Exception error) {
      jdbc.update("update question_normalization_jobs set status='FAILED',error_message=?,updated_at=? where id=?",
          limit(error.getMessage() == null ? "题库格式识别失败" : error.getMessage(), 2000), Timestamp.from(Instant.now()), id);
      throw error;
    }
  }

  public JobView get(UUID id) { JobRow job = raw(id); assertOwner(job.ownerId()); return view(job); }

  public List<ItemView> items(UUID id) {
    JobRow job = raw(id); assertOwner(job.ownerId());
    return jdbc.query("select id,sequence_no,question_json,status,error_code,error_message from question_normalization_items where job_id=? order by sequence_no",
        (rs, n) -> new ItemView(rs.getObject(1, UUID.class), rs.getInt(2), readMap(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getString(6)), id);
  }

  public CoreBusinessService.Job confirm(UUID id) {
    JobRow job = raw(id); assertOwner(job.ownerId());
    if (!"READY_FOR_REVIEW".equals(job.status())) throw new IllegalArgumentException("当前整理任务不可导入");
    List<Map<String, Object>> questions = jdbc.query("select question_json from question_normalization_items where job_id=? and status='VALID' order by sequence_no",
        (rs, n) -> readMap(rs.getString(1)), id);
    if (questions.isEmpty()) throw new IllegalArgumentException("没有通过校验的题目");
    CoreBusinessService.Job imported = core.importQuestions(questions);
    jdbc.update("update question_normalization_jobs set status='IMPORTED',valid_questions=?,updated_at=? where id=?",
        questions.size(), Timestamp.from(Instant.now()), id);
    return imported;
  }

  private void persistItems(UUID jobId, List<Map<String, Object>> questions) {
    if (questions.isEmpty()) throw new IllegalArgumentException("文件中没有识别出题目");
    int valid = 0; int invalid = 0; List<Map<String, Object>> preview = new ArrayList<>();
    for (int index = 0; index < questions.size(); index++) {
      Map<String, Object> question = questions.get(index); List<QuestionQualityChecks.Issue> issues = inspect(question);
      boolean ok = issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()));
      if (ok) valid++; else invalid++;
      String message = issues.stream().map(QuestionQualityChecks.Issue::message).reduce((a, b) -> a + "；" + b).orElse(null);
      jdbc.update("insert into question_normalization_items(id,job_id,sequence_no,question_json,status,error_code,error_message,created_at,updated_at) values(?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(), jobId, index + 1, write(question), ok ? "VALID" : "INVALID",
          ok ? null : "NORMALIZATION_FAILED", message, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
      if (preview.size() < 20) preview.add(new LinkedHashMap<>(Map.of("sequence", index + 1, "question", question, "valid", ok, "issues", issues)));
    }
    jdbc.update("update question_normalization_jobs set total_questions=?,valid_questions=?,error_questions=?,preview_json=?,updated_at=? where id=?",
        questions.size(), valid, invalid, write(preview), Timestamp.from(Instant.now()), jobId);
  }

  private List<Map<String, Object>> parseExcel(Path path) throws Exception {
    try (var workbook = WorkbookFactory.create(Files.newInputStream(path))) {
      var sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
      if (sheet == null) throw new IllegalArgumentException("Excel 中没有工作表");
      DataFormatter formatter = new DataFormatter(); var header = sheet.getRow(0);
      Map<String, Integer> columns = new LinkedHashMap<>();
      if (header != null) for (var cell : header) columns.put(normalizeHeader(formatter.formatCellValue(cell)), cell.getColumnIndex());
      List<Map<String, Object>> result = new ArrayList<>();
      for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        var row = sheet.getRow(rowIndex); if (row == null) continue;
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("sequence", rowIndex); question.put("type", cell(row, columns, formatter, "题型", "type"));
        question.put("assessmentPoint", cell(row, columns, formatter, "考点", "知识点", "assessmentpoint"));
        question.put("difficulty", cell(row, columns, formatter, "难度", "difficulty"));
        question.put("stem", cell(row, columns, formatter, "题干", "题目", "问题", "stem"));
        question.put("options", cell(row, columns, formatter, "选项", "options"));
        question.put("answer", cell(row, columns, formatter, "答案", "正确答案", "answer"));
        question.put("analysis", cell(row, columns, formatter, "解析", "答案解析", "analysis"));
        question.put("sourceRef", cell(row, columns, formatter, "原文定位", "来源", "sourceref"));
        question.put("sourceExcerpt", cell(row, columns, formatter, "原文片段", "sourceexcerpt"));
        if (!text(question.get("stem")).isBlank()) result.add(question);
      }
      return result;
    }
  }

  private List<Map<String, Object>> parseWord(Path path, boolean docx) throws Exception {
    String raw;
    if (docx) try (var document = new XWPFDocument(Files.newInputStream(path)); var extractor = new XWPFWordExtractor(document)) { raw = extractor.getText(); }
    else try (var document = new HWPFDocument(Files.newInputStream(path)); var extractor = new WordExtractor(document)) { raw = extractor.getText(); }
    List<Map<String, Object>> result = new ArrayList<>(); Map<String, Object> current = null; List<String> options = new ArrayList<>();
    for (String line : raw.replace('\r', '\n').split("\\n+")) {
      String value = line.trim(); if (value.isBlank()) continue;
      Matcher question = QUESTION.matcher(value);
      if (question.matches()) {
        if (current != null) finishWordQuestion(current, options, result);
        current = new LinkedHashMap<>(); options = new ArrayList<>(); current.put("sequence", Integer.parseInt(question.group(1)));
        current.put("stem", question.group(2).trim()); continue;
      }
      if (current == null) continue;
      Matcher option = OPTION.matcher(value);
      if (option.matches() && options.size() < 8) { options.add(option.group(2).trim()); continue; }
      if (label(value, "答案")) current.put("answer", afterLabel(value));
      else if (label(value, "解析")) current.put("analysis", afterLabel(value));
      else if (label(value, "考点") || label(value, "知识点")) current.put("assessmentPoint", afterLabel(value));
      else if (label(value, "题型")) current.put("type", afterLabel(value));
      else if (label(value, "难度")) current.put("difficulty", afterLabel(value));
      else if (text(current.get("stem")).length() < 2000) current.put("stem", text(current.get("stem")) + " " + value);
    }
    if (current != null) finishWordQuestion(current, options, result);
    return result;
  }

  private void finishWordQuestion(Map<String, Object> question, List<String> options, List<Map<String, Object>> result) {
    if (!options.isEmpty()) question.put("options", String.join("|", options));
    String explicit = text(question.get("type")).toUpperCase(Locale.ROOT);
    String answer = text(question.get("answer"));
    if (explicit.isBlank()) explicit = options.size() >= 2 ? (answer.matches("[A-D](?:[、,，][A-D])+") ? "MULTIPLE_CHOICE" : "SINGLE_CHOICE") : Set.of("正确", "错误").contains(answer) ? "TRUE_FALSE" : "SHORT_ANSWER";
    question.put("type", canonicalType(explicit));
    question.putIfAbsent("difficulty", "EASY"); question.putIfAbsent("analysis", "待人工补充解析");
    question.putIfAbsent("assessmentPoint", "待人工补充考点"); question.putIfAbsent("sourceRef", ""); question.putIfAbsent("sourceExcerpt", "");
    result.add(question);
  }

  private List<QuestionQualityChecks.Issue> inspect(Map<String, Object> question) {
    String type = canonicalType(text(question.get("type"))); question.put("type", type);
    if (text(question.get("difficulty")).isBlank()) question.put("difficulty", "EASY");
    List<QuestionQualityChecks.Issue> issues = new ArrayList<>(QuestionQualityChecks.inspect(question, type, 2));
    if (!TYPES.contains(type)) issues.add(new QuestionQualityChecks.Issue("TYPE", "ERROR", "题型无法识别"));
    if (text(question.get("assessmentPoint")).isBlank()) issues.add(new QuestionQualityChecks.Issue("ASSESSMENT_POINT", "ERROR", "考点不能为空"));
    return List.copyOf(issues);
  }

  private String canonicalType(String value) {
    String type = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "单选", "单选题", "SINGLE" -> "SINGLE_CHOICE";
      case "多选", "多选题", "MULTIPLE" -> "MULTIPLE_CHOICE";
      case "判断", "判断题", "TRUEFALSE" -> "TRUE_FALSE";
      case "填空", "填空题" -> "FILL_BLANK";
      case "简答", "简答题" -> "SHORT_ANSWER";
      case "计算", "计算题" -> "CALCULATION";
      case "论述", "论述题" -> "ESSAY";
      case "案例", "案例题" -> "CASE_ANALYSIS";
      case "综合", "综合题" -> "COMPREHENSIVE";
      default -> type;
    };
  }

  private String cell(org.apache.poi.ss.usermodel.Row row, Map<String, Integer> columns, DataFormatter formatter, String... names) {
    for (String name : names) {
      Integer index = columns.get(normalizeHeader(name));
      if (index != null) {
        var cell = row.getCell(index);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
      }
    }
    return "";
  }
  private String normalizeHeader(String value) { return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT).replaceAll("[\\s:：()（）]", ""); }
  private boolean label(String value, String label) { return value.startsWith(label + ":") || value.startsWith(label + "：") || value.equals(label); }
  private String afterLabel(String value) { int index = Math.max(value.indexOf(':'), value.indexOf('：')); return index < 0 ? "" : value.substring(index + 1).trim(); }
  private String safeName(String value) { return value.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_"); }
  private String suffix(String name) { int index = name.lastIndexOf('.'); return index < 0 ? ".bin" : name.substring(index); }
  private String mediaType(String name) { if (name.endsWith(".doc")) return "application/msword"; if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; if (name.endsWith(".xls")) return "application/vnd.ms-excel"; return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
  private void ensureEnabled() { if (!enabled) throw new IllegalStateException("QUESTION_NORMALIZATION_DISABLED：题库格式整理功能尚未启用"); }
  private void assertOwner(UUID owner) { if (!access.admin() && !owner.equals(access.currentUserId())) throw new AccessDeniedException("无权访问其他用户的整理任务"); }
  private JobRow raw(UUID id) { List<JobRow> rows = jdbc.query("select id,owner_id,original_name,media_type,status,total_questions,valid_questions,error_questions,error_message,created_at,updated_at from question_normalization_jobs where id=?", (rs, n) -> new JobRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getString(9), rs.getTimestamp(10).toInstant(), rs.getTimestamp(11).toInstant()), id); if (rows.isEmpty()) throw new IllegalArgumentException("题库整理任务不存在"); return rows.getFirst(); }
  private JobView view(JobRow row) { return new JobView(row.id(), row.originalName(), row.mediaType(), row.status(), row.totalQuestions(), row.validQuestions(), row.errorQuestions(), row.errorMessage(), row.createdAt(), row.updatedAt()); }
  private Map<String, Object> readMap(String value) { try { return json.readValue(Objects.toString(value, "{}"), new TypeReference<>() { }); } catch (Exception error) { return Map.of(); } }
  private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException("题库整理结果保存失败", error); } }
  private String text(Object value) { return Objects.toString(value, "").trim(); }
  private String limit(String value, int max) { return value == null ? null : value.substring(0, Math.min(max, value.length())); }
  private record JobRow(UUID id, UUID ownerId, String originalName, String mediaType, String status, int totalQuestions, int validQuestions, int errorQuestions, String errorMessage, Instant createdAt, Instant updatedAt) { }
  public record JobView(UUID id, String originalName, String mediaType, String status, int totalQuestions, int validQuestions, int errorQuestions, String errorMessage, Instant createdAt, Instant updatedAt) { }
  public record ItemView(UUID id, int sequence, Map<String, Object> question, String status, String errorCode, String errorMessage) { }
}
