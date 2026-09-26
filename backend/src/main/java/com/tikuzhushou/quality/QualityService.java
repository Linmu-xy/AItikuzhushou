package com.tikuzhushou.quality;

import com.tikuzhushou.core.CoreBusinessService;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

/** Deterministic delivery gate: structure, evidence grounding and content quality are all mandatory. */
@Service
public class QualityService {
  private static final List<String> GENERIC_MARKERS = List.of("以原文表述为准", "人工阅卷参考", "关键要求（",
      "符合原文规定的处理要求", "忽略关键限制条件", "待模型依据证据生成", "本题考查：");
  private final CoreBusinessService core;
  private final JdbcTemplate jdbc;

  public QualityService(CoreBusinessService core, JdbcTemplate jdbc) { this.core = core; this.jdbc = jdbc; }

  public Report inspect(UUID jobId) {
    var job = core.getJob(jobId);
    if (!"QUESTION_BANK".equals(job.type())) throw new IllegalArgumentException("只能验收题库任务");
    var rows = job.result();
    int total = rows.size(), missing = 0, generic = 0, weakAnalysis = 0, evidenceMismatch = 0;
    Set<String> stems = new HashSet<>(), points = new HashSet<>(), types = new LinkedHashSet<>();
    for (var row : rows) {
      String stem = text(row, "stem"), point = text(row, "assessmentPoint"), answer = text(row, "answer");
      String type = text(row, "type"), analysis = text(row, "analysis"), evidence = text(row, "sourceExcerpt");
      if (stem.isBlank() || point.isBlank() || answer.isBlank() || analysis.isBlank()) missing++;
      stems.add(normalize(stem));
      if (!point.isBlank()) points.add(point);
      if (!type.isBlank()) types.add(type);
      String combined = stem + answer + analysis;
      if (stem.length() < 16 || GENERIC_MARKERS.stream().anyMatch(combined::contains)) generic++;
      if (analysis.length() < 18) weakAnalysis++;
      if (!evidence.isBlank() && grounding(point, evidence) < 0.035) evidenceMismatch++;
    }
    int duplicates = total - stems.size();
    Integer expected = asInt(job.request().get("expectedTotal"));
    if (expected == null) {
      Object blueprint = job.request().get("blueprintJobId");
      try { expected = blueprint == null ? total : core.getJob(UUID.fromString(String.valueOf(blueprint))).result().size(); }
      catch (Exception ignored) { expected = total; }
    }
    int difference = Math.abs(total - expected);
    int structureErrors = (int) rows.stream().filter(this::invalidStructure).count();
    int missingSources = (int) rows.stream().filter(row -> text(row, "sourceRef").isBlank()
        || "未召回原文".equals(text(row, "sourceRef")) || text(row, "sourceExcerpt").isBlank()).count();
    boolean professionalReviewRequired = "REQUIRED".equals(Objects.toString(
        job.request().get("professionalReviewMode"), ""));
    boolean basicReviewOnly = "BASIC_ONLY".equals(Objects.toString(
        job.request().get("professionalReviewMode"), ""));
    Integer professionalReviewed = jdbc.queryForObject("select count(distinct sequence_no) from question_professional_audits where job_id=? and stage='AI_PROFESSIONAL_REVIEW'", Integer.class, jobId);
    // Correlated maximum keeps the query portable to the H2 production-smoke profile as well as PostgreSQL.
    Integer professionalRejected = jdbc.queryForObject("select count(*) from question_professional_audits current "
        + "where current.job_id=? and current.stage='AI_PROFESSIONAL_REVIEW' and not current.passed "
        + "and current.generation_attempt=(select max(latest.generation_attempt) from question_professional_audits latest "
        + "where latest.job_id=current.job_id and latest.sequence_no=current.sequence_no and latest.stage=current.stage)",
        Integer.class, jobId);
    int missingProfessionalReviews = professionalReviewRequired
        ? Math.max(0, total - Objects.requireNonNullElse(professionalReviewed, 0)) : 0;
    int rejectedProfessionalReviews = professionalReviewRequired ? Objects.requireNonNullElse(professionalRejected, 0) : 0;
    List<Finding> findings = new java.util.ArrayList<>(List.of(
        finding("数量误差", difference, "目标 " + expected + "，实际 " + total),
        finding("题干重复", duplicates, "规范化题干必须唯一"),
        finding("必填字段", missing, "题干、考点、答案、解析均不可为空"),
        new Finding("考点映射", points.isEmpty() ? "FAIL" : "PASS", points.size(), "已关联 " + points.size() + " 个考点"),
        new Finding("题型覆盖", types.isEmpty() ? "FAIL" : "PASS", types.size(), "已覆盖：" + String.join("、", types)),
        finding("题型结构", structureErrors, "选择题选项与答案、匹配和排序格式均须有效"),
        finding("原文可追溯", missingSources, "每题必须保留检索定位与原文片段")));
    if (!basicReviewOnly) findings.addAll(List.of(
        finding("内容非模板化", generic, "禁止占位答案、空泛套话和过短题干"),
        finding("解析完整性", weakAnalysis, "解析必须给出依据或评分要点"),
        finding("考点证据一致", evidenceMismatch, "考点与召回原文必须具有实质词汇重合"),
        finding("职业区分度审题", missingProfessionalReviews + rejectedProfessionalReviews,
            professionalReviewRequired ? "每题必须完成专业审题，且最新审题结果必须通过"
                : "历史或已关闭专业审题的任务不追溯要求")));
    long failures = findings.stream().filter(item -> "FAIL".equals(item.status())).count();
    int issueCount = findings.stream().filter(item -> "FAIL".equals(item.status())).mapToInt(Finding::value).sum();
    int score = Math.max(0, 100 - (int) failures * 12 - Math.min(40, issueCount * 3));
    return new Report(jobId, total, expected, types, points.size(), duplicates, score,
        failures == 0 ? "PASS" : "FAIL", findings, Instant.now());
  }

  public Preflight preflight(UUID jobId) {
    Report report = inspect(jobId); var job = core.getJob(jobId);
    int reviewTotal = jdbc.queryForObject("select count(*) from question_reviews where job_id=?", Integer.class, jobId);
    int approved = jdbc.queryForObject("select count(*) from question_reviews where job_id=? and review_status in ('APPROVED','LOCKED')", Integer.class, jobId);
    String mode = Objects.toString(job.request().get("generationMode"), "IMPORTED");
    List<String> warnings = new java.util.ArrayList<>();
    if (!"PASS".equals(report.status())) warnings.add("质量门禁未全部通过");
    if (reviewTotal == 0) warnings.add("题目尚未进入人工审核流程");
    else if (approved < reviewTotal) warnings.add("仍有 " + (reviewTotal - approved) + " 道题未人工通过或锁定");
    boolean professionalReviewRequired = "REQUIRED".equals(Objects.toString(
        job.request().get("professionalReviewMode"), ""));
    if ("FAST".equals(mode) && !professionalReviewRequired) warnings.add("该题库未启用独立职业区分度审查");
    if (report.actualTotal() != report.expectedTotal()) warnings.add("实际题量与计划题量不一致");
    return new Preflight(jobId, report.status(), report.score(), report.actualTotal(), report.expectedTotal(), mode,
        reviewTotal, approved, warnings.isEmpty(), List.copyOf(warnings), Instant.now());
  }

  private Finding finding(String name, int value, String detail) {
    return new Finding(name, value == 0 ? "PASS" : "FAIL", value, detail);
  }

  private boolean invalidStructure(Map<String, Object> row) {
    String type = text(row, "type"), options = text(row, "options"), answer = text(row, "answer");
    if ("SINGLE_CHOICE".equals(type)) return options.split("\\s*\\|\\s*").length != 4 || !answer.matches("[A-D]");
    if ("MULTIPLE_CHOICE".equals(type)) {
      long selected = java.util.regex.Pattern.compile("[A-D]").matcher(answer).results()
          .map(java.util.regex.MatchResult::group).distinct().count();
      return options.split("\\s*\\|\\s*").length != 4
          || !answer.matches("[A-D](?:[、,，][A-D])+") || selected < 2 || selected > 3;
    }
    if ("TRUE_FALSE".equals(type)) return !("正确".equals(answer) || "错误".equals(answer));
    int mapped = assessmentPointCount(row);
    if (Set.of("SHORT_ANSWER", "CALCULATION").contains(type) && mapped < 2) return true;
    if (Set.of("ESSAY", "CASE_ANALYSIS", "COMPREHENSIVE").contains(type) && mapped < 3) return true;
    return false;
  }

  private int assessmentPointCount(Map<String, Object> row) {
    Object points = row.get("assessmentPoints");
    if (points instanceof List<?> list) return (int) list.stream().filter(v -> !Objects.toString(v, "").trim().isBlank()).count();
    String legacy = text(row, "assessmentPoint"); return legacy.isBlank() ? 0 : legacy.split("[；;\\n]").length;
  }

  private double grounding(String point, String evidence) {
    Set<String> query = grams(normalize(point)), body = grams(normalize(evidence));
    if (query.isEmpty()) return 0;
    return query.stream().filter(body::contains).count() / (double) query.size();
  }

  private Set<String> grams(String value) {
    Set<String> result = new LinkedHashSet<>();
    for (int i = 0; i + 1 < value.length(); i++) result.add(value.substring(i, i + 2));
    return result;
  }

  private String text(Map<String, Object> row, String key) { return Objects.toString(row.get(key), "").trim(); }
  private Integer asInt(Object value) {
    if (value instanceof Number number) return number.intValue();
    try { return value == null ? null : Integer.parseInt(value.toString()); } catch (Exception e) { return null; }
  }
  private String normalize(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]", "");
  }

  public record Finding(String name, String status, int value, String detail) { }
  public record Report(UUID jobId, int actualTotal, int expectedTotal, Set<String> questionTypes,
      int mappedPoints, int duplicateStems, int score, String status, List<Finding> findings, Instant checkedAt) { }
  public record Preflight(UUID jobId, String qualityStatus, int qualityScore, int actualTotal, int expectedTotal,
      String generationMode, int reviewTotal, int approvedReviews, boolean deliveryReady, List<String> warnings,
      Instant checkedAt) { }
}
