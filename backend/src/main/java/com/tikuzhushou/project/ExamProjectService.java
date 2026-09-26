package com.tikuzhushou.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.cad.CadMaterialService;
import com.tikuzhushou.document.DocumentIntakeService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Durable configuration boundary for the unified standard/material exam flow. */
@Service
public class ExamProjectService {
  private static final Set<String> MODES = Set.of("KNOWLEDGE_BASE", "CAREER", "STANDARD", "MATERIAL", "FUSION");
  private static final Set<String> SOURCE_TYPES = Set.of("DOCUMENT", "CAD_MATERIAL");
  private static final Set<String> SOURCE_ROLES = Set.of("STANDARD", "TASK_BOOK", "SAMPLE", "TEMPLATE", "DRAWING", "MODEL", "OTHER");

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseAccessService access;
  private final CadMaterialService cadMaterials;
  private final DocumentIntakeService documents;

  public ExamProjectService(JdbcTemplate jdbc, ObjectMapper json, KnowledgeBaseAccessService access,
      CadMaterialService cadMaterials, DocumentIntakeService documents) {
    this.jdbc = jdbc;
    this.json = json;
    this.access = access;
    this.cadMaterials = cadMaterials;
    this.documents = documents;
  }

  public List<ProjectView> list() {
    String sql = "select p.id,p.owner_id,p.knowledge_base_id,p.name,p.mode,p.status,p.requirement_text,p.variant_count," +
        "p.difficulty_profile_json,p.scoring_structure_json,p.authorization_confirmed,p.web_search_enabled,p.created_at,p.updated_at," +
        "(select count(*) from exam_project_sources s where s.project_id=p.id and s.enabled=true) source_count " +
        "from exam_projects p " + (access.admin() ? "" : "where p.owner_id=? ") + "order by p.updated_at desc";
    return access.admin() ? jdbc.query(sql, (rs, row) -> mapProject(rs))
        : jdbc.query(sql, (rs, row) -> mapProject(rs), access.currentUserId());
  }

  public ProjectView get(UUID id) {
    ProjectView project = queryProject(id);
    assertOwner(project);
    return withSources(project);
  }

  public ProjectView create(CreateRequest request) {
    String name = cleanName(request.name());
    String mode = mode(request.mode());
    UUID knowledgeBaseId = Objects.requireNonNull(request.knowledgeBaseId(), "知识库不能为空");
    access.assertKnowledgeBase(knowledgeBaseId);
    if (!request.authorizationConfirmed()) throw new IllegalArgumentException("请先确认资料使用授权");
    int variants = boundedVariants(request.variantCount());
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.update("insert into exam_projects(id,owner_id,knowledge_base_id,name,mode,status,requirement_text,variant_count,difficulty_profile_json,scoring_structure_json,authorization_confirmed,web_search_enabled,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id, access.currentUserId(), knowledgeBaseId, name, mode, "DRAFT", cleanText(request.requirementText(), 8000), variants,
        jsonText(request.difficultyProfile(), Map.of("easy", 30, "medium", 50, "hard", 20)),
        jsonText(request.scoringStructure(), List.of()), true, "KNOWLEDGE_BASE".equals(mode) && !Boolean.FALSE.equals(request.webSearchEnabled()), Timestamp.from(now), Timestamp.from(now));
    return get(id);
  }

  public ProjectView update(UUID id, UpdateRequest request) {
    ProjectView current = get(id);
    String name = request.name() == null ? current.name() : cleanName(request.name());
    String mode = request.mode() == null ? current.mode() : mode(request.mode());
    int variants = request.variantCount() == null ? current.variantCount() : boundedVariants(request.variantCount());
    String requirement = request.requirementText() == null ? current.requirementText() : cleanText(request.requirementText(), 8000);
    String difficulty = request.difficultyProfile() == null ? current.difficultyProfileJson() : jsonText(request.difficultyProfile(), current.difficultyProfileJson());
    String scoring = request.scoringStructure() == null ? current.scoringStructureJson() : jsonText(request.scoringStructure(), current.scoringStructureJson());
    boolean authorized = request.authorizationConfirmed() == null ? current.authorizationConfirmed() : request.authorizationConfirmed();
    if (!authorized) throw new IllegalArgumentException("项目必须保持资料使用授权确认");
    boolean webSearchEnabled = "KNOWLEDGE_BASE".equals(mode)
        && (request.webSearchEnabled() == null ? current.webSearchEnabled() : request.webSearchEnabled());
    jdbc.update("update exam_projects set name=?,mode=?,requirement_text=?,variant_count=?,difficulty_profile_json=?,scoring_structure_json=?,authorization_confirmed=?,web_search_enabled=?,updated_at=? where id=?",
        name, mode, requirement, variants, difficulty, scoring, true, webSearchEnabled, Timestamp.from(Instant.now()), id);
    return get(id);
  }

  public SourceView addSource(UUID projectId, SourceRequest request) {
    ProjectView project = get(projectId);
    String type = sourceType(request.sourceType());
    String role = sourceRole(request.sourceRole());
    UUID sourceId = Objects.requireNonNull(request.sourceId(), "资料不能为空");
    UUID documentId = null;
    UUID cadId = null;
    String name;
    String status;
    String version;
    if ("DOCUMENT".equals(type)) {
      access.assertDocument(sourceId);
      SourceDocument source = jdbc.query("select d.id,d.knowledge_base_id,d.original_name,d.status,d.content_sha256 from source_documents d where d.id=?",
          (rs, row) -> new SourceDocument(rs.getObject("id", UUID.class), rs.getObject("knowledge_base_id", UUID.class), rs.getString("original_name"), rs.getString("status"), rs.getString("content_sha256")), sourceId)
          .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("文档资料不存在"));
      if (!source.knowledgeBaseId().equals(project.knowledgeBaseId())) throw new AccessDeniedException("资料与项目知识库不一致");
      documentId = source.id(); name = source.name(); status = source.status();
      version = source.hash() == null || source.hash().isBlank() ? documents.ensureSha256(source.id()) : source.hash();
    } else {
      CadMaterialService.Material source = cadMaterials.get(sourceId);
      if (!source.knowledgeBaseId().equals(project.knowledgeBaseId())) throw new AccessDeniedException("模型资料与项目知识库不一致");
      cadId = source.id(); name = source.originalName(); status = source.status(); version = source.sha256();
    }
    UUID existing = jdbc.query("select id from exam_project_sources where project_id=? and " +
            (documentId != null ? "source_document_id=?" : "cad_material_id=?") + " and source_role=?",
        (rs, row) -> rs.getObject(1, UUID.class), projectId, documentId != null ? documentId : cadId, role)
        .stream().findFirst().orElse(null);
    Instant now = Instant.now();
    if (existing != null) {
      jdbc.update("update exam_project_sources set enabled=true,version_ref=? where id=?", version, existing);
      return source(existing);
    }
    UUID id = UUID.randomUUID();
    jdbc.update("insert into exam_project_sources(id,project_id,source_type,source_document_id,cad_material_id,source_role,version_ref,enabled,created_at) values(?,?,?,?,?,?,?,?,?)",
        id, projectId, type, documentId, cadId, role, version, true, Timestamp.from(now));
    jdbc.update("update exam_projects set updated_at=? where id=?", Timestamp.from(now), projectId);
    return new SourceView(id, type, documentId != null ? documentId : cadId, role, name, status, true, version, now);
  }

  public void removeSource(UUID projectId, UUID sourceId) {
    get(projectId);
    int changed = jdbc.update("delete from exam_project_sources where id=? and project_id=?", sourceId, projectId);
    if (changed == 0) throw new IllegalArgumentException("项目资料不存在");
    jdbc.update("update exam_projects set updated_at=? where id=?", Timestamp.from(Instant.now()), projectId);
  }

  private ProjectView withSources(ProjectView project) {
    List<SourceView> sources = jdbc.query("select s.id,s.source_type,s.source_document_id,s.cad_material_id,s.source_role,s.version_ref,s.enabled,s.created_at,d.original_name document_name,d.status document_status,m.original_name cad_name,m.status cad_status from exam_project_sources s left join source_documents d on d.id=s.source_document_id left join cad_materials m on m.id=s.cad_material_id where s.project_id=? order by s.created_at,s.id",
        (rs, row) -> new SourceView(rs.getObject("id", UUID.class), rs.getString("source_type"),
            rs.getObject("source_document_id", UUID.class) != null ? rs.getObject("source_document_id", UUID.class) : rs.getObject("cad_material_id", UUID.class),
            rs.getString("source_role"), Objects.requireNonNullElse(rs.getString("document_name"), rs.getString("cad_name")),
            Objects.requireNonNullElse(rs.getString("document_status"), rs.getString("cad_status")), rs.getBoolean("enabled"), rs.getString("version_ref"), rs.getTimestamp("created_at").toInstant()), project.id());
    return project.withSources(sources);
  }

  private SourceView source(UUID sourceId) {
    return jdbc.query("select s.id,s.source_type,s.source_document_id,s.cad_material_id,s.source_role,s.version_ref,s.enabled,s.created_at,d.original_name document_name,d.status document_status,m.original_name cad_name,m.status cad_status from exam_project_sources s left join source_documents d on d.id=s.source_document_id left join cad_materials m on m.id=s.cad_material_id where s.id=?",
        (rs, row) -> new SourceView(rs.getObject("id", UUID.class), rs.getString("source_type"),
            rs.getObject("source_document_id", UUID.class) != null ? rs.getObject("source_document_id", UUID.class) : rs.getObject("cad_material_id", UUID.class),
            rs.getString("source_role"), Objects.requireNonNullElse(rs.getString("document_name"), rs.getString("cad_name")),
            Objects.requireNonNullElse(rs.getString("document_status"), rs.getString("cad_status")), rs.getBoolean("enabled"), rs.getString("version_ref"), rs.getTimestamp("created_at").toInstant()), sourceId)
        .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("项目资料不存在"));
  }

  private ProjectView queryProject(UUID id) {
    return jdbc.query("select p.id,p.owner_id,p.knowledge_base_id,p.name,p.mode,p.status,p.requirement_text,p.variant_count,p.difficulty_profile_json,p.scoring_structure_json,p.authorization_confirmed,p.web_search_enabled,p.created_at,p.updated_at,(select count(*) from exam_project_sources s where s.project_id=p.id and s.enabled=true) source_count from exam_projects p where p.id=?",
        (rs, row) -> mapProject(rs), id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("命题项目不存在"));
  }

  private void assertOwner(ProjectView project) {
    if (!access.admin() && !project.ownerId().equals(access.currentUserId())) throw new AccessDeniedException("无权访问其他用户的命题项目");
  }
  private ProjectView mapProject(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ProjectView(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getObject("knowledge_base_id", UUID.class),
        rs.getString("name"), rs.getString("mode"), rs.getString("status"), rs.getString("requirement_text"), rs.getInt("variant_count"),
        rs.getString("difficulty_profile_json"), rs.getString("scoring_structure_json"), rs.getBoolean("authorization_confirmed"), rs.getBoolean("web_search_enabled"),
        rs.getInt("source_count"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), List.of());
  }
  private String cleanName(String value) { String result = Objects.toString(value, "").trim(); if (result.isBlank() || result.length() > 180) throw new IllegalArgumentException("项目名称不能为空且不能超过 180 个字符"); return result; }
  private String cleanText(String value, int max) { String result = Objects.toString(value, "").trim(); return result.substring(0, Math.min(max, result.length())); }
  private String mode(String value) { String result = Objects.toString(value, "KNOWLEDGE_BASE").trim().toUpperCase(Locale.ROOT); if (!MODES.contains(result)) throw new IllegalArgumentException("命题模式无效"); return result; }
  private String sourceType(String value) { String result = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT); if (!SOURCE_TYPES.contains(result)) throw new IllegalArgumentException("资料类型无效"); return result; }
  private String sourceRole(String value) { String result = Objects.toString(value, "OTHER").trim().toUpperCase(Locale.ROOT); if (!SOURCE_ROLES.contains(result)) throw new IllegalArgumentException("资料角色无效"); return result; }
  private int boundedVariants(Integer value) { int result = value == null ? 1 : value; if (result < 1 || result > 20) throw new IllegalArgumentException("变式卷数量必须为 1-20 套"); return result; }
  private String jsonText(Object value, Object fallback) { try { return json.writeValueAsString(value == null ? fallback : value); } catch (Exception error) { throw new IllegalArgumentException("项目配置格式无效"); } }
  private String jsonText(Object value, String fallback) { try { return json.writeValueAsString(value == null ? json.readValue(fallback, Object.class) : value); } catch (Exception error) { throw new IllegalArgumentException("项目配置格式无效"); } }

  public record CreateRequest(String name, UUID knowledgeBaseId, String mode, String requirementText, Integer variantCount,
      Object difficultyProfile, Object scoringStructure, boolean authorizationConfirmed, Boolean webSearchEnabled) { }
  public record UpdateRequest(String name, String mode, String requirementText, Integer variantCount, Object difficultyProfile,
      Object scoringStructure, Boolean authorizationConfirmed, Boolean webSearchEnabled) { }
  public record SourceRequest(String sourceType, UUID sourceId, String sourceRole) { }
  public record ProjectView(UUID id, UUID ownerId, UUID knowledgeBaseId, String name, String mode, String status,
      String requirementText, int variantCount, String difficultyProfileJson, String scoringStructureJson,
      boolean authorizationConfirmed, boolean webSearchEnabled, int sourceCount, Instant createdAt, Instant updatedAt, List<SourceView> sources) {
    public ProjectView(UUID id, UUID ownerId, UUID knowledgeBaseId, String name, String mode, String status,
        String requirementText, int variantCount, String difficultyProfileJson, String scoringStructureJson,
        boolean authorizationConfirmed, int sourceCount, Instant createdAt, Instant updatedAt, List<SourceView> sources) {
      this(id, ownerId, knowledgeBaseId, name, mode, status, requirementText, variantCount, difficultyProfileJson,
          scoringStructureJson, authorizationConfirmed, false, sourceCount, createdAt, updatedAt, sources);
    }
    ProjectView withSources(List<SourceView> value) { return new ProjectView(id, ownerId, knowledgeBaseId, name, mode, status, requirementText, variantCount, difficultyProfileJson, scoringStructureJson, authorizationConfirmed, webSearchEnabled, sourceCount, createdAt, updatedAt, value); }
  }
  public record SourceView(UUID id, String sourceType, UUID sourceId, String sourceRole, String name, String status,
      boolean enabled, String versionRef, Instant createdAt) { }
  private record SourceDocument(UUID id, UUID knowledgeBaseId, String name, String status, String hash) { }
}
