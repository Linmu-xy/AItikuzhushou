package com.tikuzhushou.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.document.DocumentIntakeService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Validates and freezes all project-level evidence before a material exam generation task. */
@Service
public class ExamProjectEvidenceService {
  private static final Set<String> MATERIAL_ROLES = Set.of("TASK_BOOK", "SAMPLE", "TEMPLATE");
  private static final Set<String> ANALYZED_CAD_STATUS = Set.of("ANALYZED", "ANALYSIS_PARTIAL");
  private static final Set<String> ANALYSIS_READY_STATUS = Set.of("PARSED", "PARSED_PARTIAL");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseAccessService access;
  private final ExamProjectService projects;
  private final DocumentIntakeService documents;

  public ExamProjectEvidenceService(JdbcTemplate jdbc, ObjectMapper json,
      KnowledgeBaseAccessService access, ExamProjectService projects, DocumentIntakeService documents) {
    this.jdbc = jdbc;
    this.json = json;
    this.access = access;
    this.projects = projects;
    this.documents = documents;
  }

  /** Creates an immutable source/fact/profile snapshot. A blocked snapshot is retained for audit. */
  public PreparationView prepare(UUID projectId) {
    ExamProjectService.ProjectView project = projects.get(projectId);
    Validation value = inspect(project);
    Instant now = Instant.now();
    int version = nextVersion(projectId);
    UUID snapshotId = UUID.randomUUID();
    try {
      String blockersJson = json.writeValueAsString(value.blockers());
      String sourcesJson = json.writeValueAsString(value.sources());
      String factsJson = json.writeValueAsString(value.facts());
      String profilesJson = json.writeValueAsString(value.profiles());
      String hash = sha256(project.id() + "\n" + version + "\n" + blockersJson + "\n" + sourcesJson
          + "\n" + factsJson + "\n" + profilesJson);
      jdbc.update("insert into exam_project_evidence_snapshots(id,project_id,snapshot_version,status,blockers_json,sources_json,facts_json,profiles_json,snapshot_hash,created_by,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",
          snapshotId, projectId, version, value.blockers().isEmpty() ? "READY" : "BLOCKED", blockersJson,
          sourcesJson, factsJson, profilesJson, hash, access.currentUserId(), Timestamp.from(now));
      return new PreparationView(snapshotId, projectId, version,
          value.blockers().isEmpty() ? "READY" : "BLOCKED", value.blockers(), value.sources(), value.facts().size(),
          value.profiles(), hash, now);
    } catch (DuplicateKeyException raced) {
      return prepare(projectId);
    } catch (Exception error) {
      throw new IllegalStateException("保存命题依据快照失败", error);
    }
  }

  public PreparationView latest(UUID projectId) {
    projects.get(projectId);
    return jdbc.query("select s.id,s.project_id,s.snapshot_version,s.status,s.blockers_json,s.sources_json,s.facts_json,s.profiles_json,s.snapshot_hash,s.created_at,p.updated_at from exam_project_evidence_snapshots s join exam_projects p on p.id=s.project_id where s.project_id=? order by s.snapshot_version desc limit 1",
        (rs, row) -> {
          Instant createdAt = rs.getTimestamp("created_at").toInstant();
          boolean stale = rs.getTimestamp("updated_at").toInstant().isAfter(createdAt);
          List<String> blockers = new ArrayList<>(strings(rs.getString("blockers_json")));
          if (stale && !blockers.contains("项目配置或资料事实已在快照后发生变化，请重新冻结")) {
            blockers.add("项目配置或资料事实已在快照后发生变化，请重新冻结");
          }
          return new PreparationView(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
              rs.getInt("snapshot_version"), stale ? "BLOCKED" : rs.getString("status"), blockers,
              maps(rs.getString("sources_json")), maps(rs.getString("facts_json")).size(),
              jsonMap(rs.getString("profiles_json")), rs.getString("snapshot_hash"), createdAt);
        }, projectId)
        .stream().findFirst().orElse(null);
  }

  private Validation inspect(ExamProjectService.ProjectView project) {
    List<String> blockers = new ArrayList<>();
    List<Map<String, Object>> sources = new ArrayList<>();
    List<Map<String, Object>> facts = new ArrayList<>();
    Map<String, Object> profiles = new LinkedHashMap<>();
    Set<String> roles = project.sources().stream().filter(ExamProjectService.SourceView::enabled)
        .map(ExamProjectService.SourceView::sourceRole).collect(java.util.stream.Collectors.toSet());
    if (!project.authorizationConfirmed()) blockers.add("项目尚未确认资料使用授权");
    if (Set.of("CAREER", "STANDARD", "FUSION").contains(project.mode()) && !roles.contains("STANDARD")) {
      blockers.add("职业命题必须关联一份已确认的职业标准");
    }
    if (Set.of("MATERIAL", "FUSION").contains(project.mode())) {
      for (String role : MATERIAL_ROLES) if (!roles.contains(role)) blockers.add("缺少资料角色：" + roleLabel(role));
    }
    if ("KNOWLEDGE_BASE".equals(project.mode()) && project.sources().stream()
        .noneMatch(source -> source.enabled() && "DOCUMENT".equals(source.sourceType()))) {
      blockers.add("请至少选择一份知识库文档");
    }
    for (ExamProjectService.SourceView source : project.sources()) {
      if (!source.enabled()) continue;
      Map<String, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("sourceId", source.sourceId());
      snapshot.put("sourceType", source.sourceType());
      snapshot.put("sourceRole", source.sourceRole());
      snapshot.put("name", source.name());
      snapshot.put("status", source.status());
      String versionRef = source.versionRef();
      if ((versionRef == null || versionRef.isBlank()) && "DOCUMENT".equals(source.sourceType())) {
        versionRef = documents.ensureSha256(source.sourceId());
        jdbc.update("update exam_project_sources set version_ref=? where id=? and (version_ref is null or trim(version_ref)='')",
            versionRef, source.id());
      }
      snapshot.put("versionRef", Objects.toString(versionRef, ""));
      if (versionRef == null || versionRef.isBlank()) {
        blockers.add("资料缺少版本指纹：" + source.name());
      }
      if ("DOCUMENT".equals(source.sourceType())) {
        DocumentEvidence document = document(source.sourceId());
        snapshot.put("chunkCount", document.chunkCount());
        snapshot.put("profile", document.profile());
        if (!"PARSED".equals(document.status())
            && !("KNOWLEDGE_BASE".equals(project.mode()) && "PARSED_PARTIAL".equals(document.status()))) {
          blockers.add("文档尚未解析完成：" + document.name());
        }
        if ("KNOWLEDGE_BASE".equals(project.mode()) && document.chunkCount() == 0
            && !hasReadableVisual(source.sourceId())) {
          blockers.add("文档没有可用于出题的正文或原图：" + document.name());
        }
        if ("STANDARD".equals(source.sourceRole()) && document.confirmedStandardCount() == 0) {
          blockers.add("职业标准尚未人工确认：" + document.name());
        }
        if ("SAMPLE".equals(source.sourceRole())) profiles.put("sample:" + source.sourceId(), document.profile());
        if ("TEMPLATE".equals(source.sourceRole())) profiles.put("template:" + source.sourceId(), document.profile());
      } else if ("CAD_MATERIAL".equals(source.sourceType())) {
        CadEvidence cad = cad(source.sourceId());
        snapshot.put("analysisStatus", cad.analysisStatus());
        snapshot.put("usableFactCount", cad.factCount());
        snapshot.put("usableAnnotationCount", cad.annotationCount());
        if (!ANALYZED_CAD_STATUS.contains(cad.materialStatus()) || !ANALYSIS_READY_STATUS.contains(cad.analysisStatus())) {
          blockers.add("模型或工程图尚未完成读取：" + cad.name());
        } else if (cad.factCount() + cad.annotationCount() == 0) {
          blockers.add("模型或工程图没有已确认的可出题事实：" + cad.name());
        }
        facts.addAll(cad.facts());
      }
      sources.add(snapshot);
    }
    if (project.sources().stream().noneMatch(ExamProjectService.SourceView::enabled)) blockers.add("项目尚未关联任何资料");
    profiles.put("requirementText", Objects.toString(project.requirementText(), ""));
    profiles.put("difficultyProfileJson", project.difficultyProfileJson());
    profiles.put("scoringStructureJson", project.scoringStructureJson());
    profiles.put("variantCount", project.variantCount());
    return new Validation(List.copyOf(blockers), List.copyOf(sources), List.copyOf(facts), Map.copyOf(profiles));
  }

  private DocumentEvidence document(UUID id) {
    List<DocumentEvidence> rows = jdbc.query("select id,original_name,status,content_sha256 from source_documents where id=?",
        (rs, row) -> new DocumentEvidence(rs.getObject("id", UUID.class), rs.getString("original_name"),
            rs.getString("status"), rs.getString("content_sha256"), 0, Map.of(), 0), id);
    if (rows.isEmpty()) throw new IllegalArgumentException("项目引用的文档资料不存在");
    DocumentEvidence base = rows.getFirst();
    Integer chunkCount = jdbc.queryForObject("select count(*) from document_chunks where document_id=? and trim(content)<>''", Integer.class, id);
    List<String> chunks = jdbc.query("select content from document_chunks where document_id=? order by chunk_index limit 80",
        (rs, row) -> rs.getString(1), id);
    String content = compact(String.join("\n", chunks), 60_000);
    Map<String, Object> profile = profile(content, chunkCount == null ? 0 : chunkCount);
    Integer standards = jdbc.queryForObject("select count(*) from occupational_standards where document_id=? and status='CONFIRMED'", Integer.class, id);
    return new DocumentEvidence(base.id(), base.name(), base.status(), base.hash(), chunkCount == null ? 0 : chunkCount,
        profile, standards == null ? 0 : standards);
  }

  private boolean hasReadableVisual(UUID id) {
    String mediaType = jdbc.query("select media_type from source_documents where id=?",
        (rs, row) -> Objects.toString(rs.getString(1), ""), id).stream().findFirst().orElse("");
    if (mediaType.startsWith("image/")) return true;
    if (!"application/pdf".equals(mediaType)) return false;
    Integer pages = jdbc.queryForObject("select count(*) from document_pages where document_id=? and active=true",
        Integer.class, id);
    return pages != null && pages > 0;
  }

  private CadEvidence cad(UUID id) {
    List<CadRow> rows = jdbc.query("select m.id,m.original_name,m.status,a.status analysis_status,a.id analysis_id from cad_materials m left join cad_analysis_jobs a on a.id=(select j.id from cad_analysis_jobs j where j.material_id=m.id order by j.created_at desc limit 1) where m.id=?",
        (rs, row) -> new CadRow(rs.getObject("id", UUID.class), rs.getString("original_name"), rs.getString("status"),
            Objects.requireNonNullElse(rs.getString("analysis_status"), ""), rs.getObject("analysis_id", UUID.class)), id);
    if (rows.isEmpty()) throw new IllegalArgumentException("项目引用的模型或工程图不存在");
    CadRow row = rows.getFirst();
    List<Map<String, Object>> facts = jdbc.query("select id,fact_name,value_json,unit,source_ref,confidence,verified_at from cad_facts where material_id=? and analysis_job_id=? and verified=true and usable_for_generation=true order by fact_name,id",
        (rs, index) -> fact(rs.getObject("id", UUID.class), row.id(), "FACT", rs.getString("fact_name"), rs.getString("value_json"), rs.getString("unit"), rs.getString("source_ref"), rs.getDouble("confidence"), rs.getTimestamp("verified_at")), id, row.analysisId());
    List<Map<String, Object>> annotations = jdbc.query("select id,annotation_kind,value_json,source_ref,confidence,verified_at from cad_annotations where material_id=? and analysis_job_id=? and verified=true and usable_for_generation=true order by page_number,id",
        (rs, index) -> fact(rs.getObject("id", UUID.class), row.id(), "ANNOTATION", rs.getString("annotation_kind"), rs.getString("value_json"), "", rs.getString("source_ref"), rs.getDouble("confidence"), rs.getTimestamp("verified_at")), id, row.analysisId());
    List<Map<String, Object>> all = new ArrayList<>(facts); all.addAll(annotations);
    return new CadEvidence(row.id(), row.name(), row.materialStatus(), row.analysisStatus(), all.size(), annotations.size(), all);
  }

  private Map<String, Object> fact(UUID factId, UUID materialId, String kind, String name, String valueJson,
      String unit, String sourceRef, double confidence, Timestamp verifiedAt) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("id", factId); value.put("materialId", materialId); value.put("kind", kind); value.put("name", name);
    value.put("value", jsonValue(valueJson)); value.put("unit", unit); value.put("sourceRef", sourceRef);
    value.put("confidence", confidence); value.put("verifiedAt", verifiedAt == null ? "" : verifiedAt.toInstant());
    return value;
  }

  private Map<String, Object> profile(String raw, int chunkCount) {
    String content = compact(raw, 60_000);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("chunkCount", chunkCount);
    Map<String, Integer> types = new LinkedHashMap<>();
    count(types, "SINGLE_CHOICE", content, "单选题", "单项选择题");
    count(types, "MULTIPLE_CHOICE", content, "多选题", "多项选择题");
    count(types, "TRUE_FALSE", content, "判断题", "判断题型");
    count(types, "FILL_BLANK", content, "填空题");
    count(types, "SHORT_ANSWER", content, "简答题");
    count(types, "CASE_ANALYSIS", content, "案例分析题", "案例题");
    count(types, "COMPREHENSIVE", content, "综合题");
    result.put("questionTypeHints", types);
    Map<String, Integer> difficulty = new LinkedHashMap<>();
    count(difficulty, "EASY", content, "简单", "基础");
    count(difficulty, "MEDIUM", content, "中等", "一般");
    count(difficulty, "HARD", content, "困难", "综合", "提高");
    result.put("difficultyHints", difficulty);
    Map<String, Boolean> templateFields = new LinkedHashMap<>();
    for (String field : List.of("题干", "选项", "答案", "解析", "评分标准", "分值", "考点")) templateFields.put(field, content.contains(field));
    result.put("templateFields", templateFields);
    return result;
  }

  private void count(Map<String, Integer> target, String key, String content, String... terms) {
    int total = 0; for (String term : terms) total += occurrences(content, term); if (total > 0) target.put(key, total);
  }
  private int occurrences(String content, String term) { int count = 0; int from = 0; while ((from = content.indexOf(term, from)) >= 0) { count++; from += term.length(); } return count; }
  private int nextVersion(UUID projectId) { Integer value = jdbc.queryForObject("select coalesce(max(snapshot_version),0)+1 from exam_project_evidence_snapshots where project_id=?", Integer.class, projectId); return value == null ? 1 : value; }
  private String roleLabel(String value) { return Map.of("TASK_BOOK", "任务书", "SAMPLE", "样题", "TEMPLATE", "试题模板").getOrDefault(value, value); }
  private String compact(String value, int maximum) { String clean = WHITESPACE.matcher(Objects.toString(value, "")).replaceAll(" ").trim(); return clean.substring(0, Math.min(clean.length(), maximum)); }
  private Object jsonValue(String value) { try { return json.readValue(Objects.toString(value, "null"), Object.class); } catch (Exception ignored) { return Objects.toString(value, ""); } }
  private List<String> strings(String raw) { try { return json.readValue(Objects.toString(raw, "[]"), new TypeReference<>() { }); } catch (Exception ignored) { return List.of(); } }
  private List<Map<String, Object>> maps(String raw) { try { return json.readValue(Objects.toString(raw, "[]"), new TypeReference<>() { }); } catch (Exception ignored) { return List.of(); } }
  private Map<String, Object> jsonMap(String raw) { try { return json.readValue(Objects.toString(raw, "{}"), new TypeReference<>() { }); } catch (Exception ignored) { return Map.of(); } }
  private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception error) { throw new IllegalStateException("计算命题依据指纹失败", error); } }

  private record Validation(List<String> blockers, List<Map<String, Object>> sources, List<Map<String, Object>> facts, Map<String, Object> profiles) { }
  private record DocumentEvidence(UUID id, String name, String status, String hash, int chunkCount, Map<String, Object> profile, int confirmedStandardCount) { }
  private record CadRow(UUID id, String name, String materialStatus, String analysisStatus, UUID analysisId) { }
  private record CadEvidence(UUID id, String name, String materialStatus, String analysisStatus, int factCount, int annotationCount, List<Map<String, Object>> facts) { }
  public record PreparationView(UUID snapshotId, UUID projectId, int version, String status, List<String> blockers,
      List<Map<String, Object>> sources, int factCount, Map<String, Object> profiles, String snapshotHash, Instant createdAt) { }
}
