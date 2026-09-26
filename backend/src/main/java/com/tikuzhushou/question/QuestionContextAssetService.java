package com.tikuzhushou.question;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import com.tikuzhushou.retrieval.RetrievalService;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Curated, local-only context for realistic assessment scenarios.  These assets are deliberately
 * separated from occupational-standard evidence: they may make a distractor plausible but never
 * establish the correct answer.
 */
@Service
public class QuestionContextAssetService {
  private static final Set<String> TYPES = Set.of("CONTEXT", "EXAM_PATTERN", "TEMPORAL_CONTEXT");
  private static final Set<String> LICENSES = Set.of("OWNED", "LICENSED", "PUBLIC_OFFICIAL", "INTERNAL", "UNKNOWN");
  private static final Set<String> TRUSTS = Set.of("OFFICIAL", "INDUSTRY", "ENTERPRISE", "USER_SUPPLIED");
  private static final Set<String> APPROVALS = Set.of("APPROVED", "REJECTED");
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseAccessService access;

  public QuestionContextAssetService(JdbcTemplate jdbc, ObjectMapper json, KnowledgeBaseAccessService access) {
    this.jdbc = jdbc;
    this.json = json;
    this.access = access;
  }

  public Asset create(UUID knowledgeBaseId, Upsert request) {
    access.assertKnowledgeBase(knowledgeBaseId);
    Normalized value = normalize(request, null, knowledgeBaseId);
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    jdbc.update("insert into question_context_assets(id,knowledge_base_id,source_document_id,asset_type,title,source_organization,source_url,license_status,profession,occupational_level,region,published_at,valid_from,valid_until,trust_level,content,content_hash,status,created_by,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id, knowledgeBaseId, value.sourceDocumentId(), value.type(), value.title(), value.sourceOrganization(), value.sourceUrl(),
        value.licenseStatus(), value.profession(), value.level(), value.region(), timestamp(value.publishedAt()),
        timestamp(value.validFrom()), timestamp(value.validUntil()), value.trustLevel(), value.content(), hash(value.content()),
        "PENDING", access.currentUserId(), timestamp(now), timestamp(now));
    return find(id);
  }

  /** Any content or metadata change returns the asset to the review queue. */
  public Asset update(UUID id, Upsert request) {
    Asset current = find(id);
    access.assertKnowledgeBase(current.knowledgeBaseId());
    Normalized value = normalize(request, current, current.knowledgeBaseId());
    Instant now = Instant.now();
    jdbc.update("update question_context_assets set source_document_id=?,asset_type=?,title=?,source_organization=?,source_url=?,license_status=?,profession=?,occupational_level=?,region=?,published_at=?,valid_from=?,valid_until=?,trust_level=?,content=?,content_hash=?,status='PENDING',approved_by=null,approved_at=null,updated_at=? where id=?",
        value.sourceDocumentId(), value.type(), value.title(), value.sourceOrganization(), value.sourceUrl(), value.licenseStatus(),
        value.profession(), value.level(), value.region(), timestamp(value.publishedAt()), timestamp(value.validFrom()),
        timestamp(value.validUntil()), value.trustLevel(), value.content(), hash(value.content()), timestamp(now), id);
    return find(id);
  }

  public Asset approve(UUID id, String decision) {
    Asset current = find(id);
    access.assertKnowledgeBase(current.knowledgeBaseId());
    if (!access.admin()) throw new org.springframework.security.access.AccessDeniedException("只有管理员可以审核命题背景资料");
    String target = normalizedDecision(decision);
    Instant now = Instant.now();
    jdbc.update("update question_context_assets set status=?,approved_by=?,approved_at=?,updated_at=? where id=?",
        target, access.currentUserId(), timestamp(now), timestamp(now), id);
    return find(id);
  }

  public List<Asset> list(UUID knowledgeBaseId) {
    access.assertKnowledgeBase(knowledgeBaseId);
    return jdbc.query("select * from question_context_assets where knowledge_base_id=? order by created_at desc",
        (rs, row) -> map(rs), knowledgeBaseId);
  }

  /** Builds the bounded local evidence pack used only by PROFESSIONAL_PRO generation. */
  public EvidencePack evidencePack(UUID knowledgeBaseId, String level, List<RetrievalService.Hit> standardHits,
      int maximumCharacters) {
    int maximum = Math.max(2_000, Math.min(maximumCharacters, 12_000));
    List<Map<String, Object>> standards = new ArrayList<>();
    StringBuilder standardText = new StringBuilder();
    for (RetrievalService.Hit hit : standardHits) {
      String excerpt = compact(hit.content(), 1_800);
      if (excerpt.isBlank()) continue;
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("sourceId", hit.chunkId().toString());
      source.put("sourceRef", hit.sourceRef());
      source.put("excerpt", excerpt);
      standards.add(source);
      appendBounded(standardText, "【" + hit.sourceRef() + "】\n" + excerpt, maximum);
    }
    if (standardText.isEmpty()) throw new IllegalArgumentException("深度命题缺少可用职业标准证据");

    Instant now = Instant.now();
    List<Asset> candidates = jdbc.query("select * from question_context_assets where knowledge_base_id=? and status='APPROVED' "
            + "and (valid_from is null or valid_from<=?) and (valid_until is null or valid_until>?) "
            + "and (occupational_level is null or trim(occupational_level)='' or occupational_level=?) "
            + "order by case trust_level when 'OFFICIAL' then 1 when 'INDUSTRY' then 2 when 'ENTERPRISE' then 3 else 4 end, updated_at desc limit 6",
        (rs, row) -> map(rs), knowledgeBaseId, timestamp(now), timestamp(now), Objects.toString(level, ""));
    List<Map<String, Object>> contexts = new ArrayList<>();
    int remaining = Math.max(0, maximum - standardText.length());
    for (Asset asset : candidates) {
      if (remaining < 180) break;
      int allowed = Math.min(1_400, remaining);
      String content = compact(asset.content(), allowed);
      if (content.isBlank()) continue;
      Map<String, Object> context = new LinkedHashMap<>();
      context.put("assetId", asset.id().toString());
      context.put("type", asset.type());
      context.put("title", asset.title());
      context.put("trustLevel", asset.trustLevel());
      context.put("content", content);
      contexts.add(context);
      remaining -= content.length();
    }
    return new EvidencePack(List.copyOf(standards), standardText.toString(), List.copyOf(contexts));
  }

  /** Recovered workers must use the source snapshot captured for the original task, not newly edited assets. */
  public EvidencePack frozenOrBuild(UUID jobId, int sequence, UUID knowledgeBaseId, String level,
      List<RetrievalService.Hit> standardHits, int maximumCharacters) {
    List<EvidencePack> frozen = jdbc.query("select standard_evidence_json,context_assets_json from question_evidence_snapshots where job_id=? and sequence_no=?",
        (rs, row) -> packFromSnapshot(maps(rs.getString(1)), maps(rs.getString(2))), jobId, sequence);
    if (!frozen.isEmpty()) return frozen.getFirst();
    EvidencePack fresh = evidencePack(knowledgeBaseId, level, standardHits, maximumCharacters);
    freeze(jobId, sequence, fresh);
    return fresh;
  }

  /** Stores the bounded evidence actually supplied to the model; the snapshot APIs always redact content. */
  public void freeze(UUID jobId, int sequence, EvidencePack pack) {
    try {
      List<Map<String, Object>> contexts = new ArrayList<>();
      for (Map<String, Object> context : pack.contexts()) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("assetId", "type", "title", "trustLevel", "content")) safe.put(key, context.get(key));
        contexts.add(safe);
      }
      String standardJson = json.writeValueAsString(pack.standardSources());
      String contextJson = json.writeValueAsString(contexts);
      String snapshotHash = hash(standardJson + "\n" + contextJson);
      jdbc.update("insert into question_evidence_snapshots(id,job_id,sequence_no,standard_evidence_json,context_assets_json,snapshot_hash,created_at) values(?,?,?,?,?,?,?)",
          UUID.randomUUID(), jobId, sequence, standardJson, contextJson, snapshotHash, timestamp(Instant.now()));
    } catch (DuplicateKeyException ignored) {
      // A recovered worker must reuse the original source identity instead of creating a second snapshot.
    } catch (Exception error) {
      throw new IllegalStateException("冻结深度命题证据快照失败", error);
    }
  }

  public List<EvidenceSnapshot> snapshots(UUID jobId) {
    access.assertJob(jobId);
    return jdbc.query("select sequence_no,standard_evidence_json,context_assets_json,snapshot_hash,created_at from question_evidence_snapshots where job_id=? order by sequence_no",
        (rs, row) -> new EvidenceSnapshot(rs.getInt(1), maps(rs.getString(2)), redactContexts(maps(rs.getString(3))), rs.getString(4),
            rs.getTimestamp(5).toInstant()), jobId);
  }

  private List<Map<String, Object>> redactContexts(List<Map<String, Object>> contexts) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> context : contexts) {
      Map<String, Object> safe = new LinkedHashMap<>(context);
      safe.remove("content");
      result.add(safe);
    }
    return List.copyOf(result);
  }

  private EvidencePack packFromSnapshot(List<Map<String, Object>> standards, List<Map<String, Object>> contexts) {
    StringBuilder standardText = new StringBuilder();
    for (Map<String, Object> source : standards) {
      String reference = Objects.toString(source.get("sourceRef"), "职业标准证据");
      String excerpt = Objects.toString(source.get("excerpt"), "");
      appendBounded(standardText, "【" + reference + "】\n" + excerpt, 12_000);
    }
    List<Map<String, Object>> restoredContexts = new ArrayList<>();
    for (Map<String, Object> context : contexts) {
      Map<String, Object> restored = new LinkedHashMap<>(context);
      restored.putIfAbsent("content", "");
      restoredContexts.add(restored);
    }
    if (standardText.isEmpty()) throw new IllegalStateException("深度命题证据快照已损坏");
    return new EvidencePack(List.copyOf(standards), standardText.toString(), List.copyOf(restoredContexts));
  }

  private Normalized normalize(Upsert request, Asset current, UUID knowledgeBaseId) {
    if (request == null) throw new IllegalArgumentException("命题背景资料不能为空");
    String type = enumValue(request.type(), current == null ? "" : current.type(), TYPES, "资料类型");
    String title = text(request.title(), current == null ? "" : current.title());
    if (title.length() < 2 || title.length() > 240) throw new IllegalArgumentException("资料标题应为 2 到 240 个字符");
    UUID documentId = request.sourceDocumentId() == null ? (current == null ? null : current.sourceDocumentId()) : request.sourceDocumentId();
    if (documentId != null) {
      access.assertDocument(documentId);
      UUID actualBase = jdbc.queryForObject("select knowledge_base_id from source_documents where id=?", UUID.class, documentId);
      if (!knowledgeBaseId.equals(actualBase)) throw new IllegalArgumentException("引用文档必须属于当前知识库");
    }
    String supplied = text(request.content(), current == null ? "" : current.content());
    if (supplied.isBlank() && documentId != null) supplied = documentContent(documentId);
    if (supplied.length() < 40 || supplied.length() > 12_000) throw new IllegalArgumentException("资料摘要应为 40 到 12000 个字符");
    Instant publishedAt = value(request.publishedAt(), current == null ? null : current.publishedAt());
    Instant validFrom = value(request.validFrom(), current == null ? null : current.validFrom());
    Instant validUntil = value(request.validUntil(), current == null ? null : current.validUntil());
    if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
      throw new IllegalArgumentException("资料失效时间必须晚于生效时间");
    }
    return new Normalized(documentId, type, title, text(request.sourceOrganization(), current == null ? "" : current.sourceOrganization()),
        text(request.sourceUrl(), current == null ? "" : current.sourceUrl()),
        enumValue(request.licenseStatus(), current == null ? "UNKNOWN" : current.licenseStatus(), LICENSES, "授权状态"),
        text(request.profession(), current == null ? "" : current.profession()), text(request.level(), current == null ? "" : current.level()),
        text(request.region(), current == null ? "" : current.region()), publishedAt, validFrom, validUntil,
        enumValue(request.trustLevel(), current == null ? "USER_SUPPLIED" : current.trustLevel(), TRUSTS, "来源可信级别"), supplied);
  }

  private Asset find(UUID id) {
    List<Asset> values = jdbc.query("select * from question_context_assets where id=?", (rs, row) -> map(rs), id);
    if (values.isEmpty()) throw new IllegalArgumentException("命题背景资料不存在");
    return values.getFirst();
  }

  private Asset map(java.sql.ResultSet rs) throws java.sql.SQLException {
    Instant validUntil = instant(rs.getTimestamp("valid_until"));
    String status = rs.getString("status");
    if ("APPROVED".equals(status) && validUntil != null && !validUntil.isAfter(Instant.now())) status = "EXPIRED";
    return new Asset(rs.getObject("id", UUID.class), rs.getObject("knowledge_base_id", UUID.class),
        rs.getObject("source_document_id", UUID.class), rs.getString("asset_type"), rs.getString("title"),
        empty(rs.getString("source_organization")), empty(rs.getString("source_url")), rs.getString("license_status"),
        empty(rs.getString("profession")), empty(rs.getString("occupational_level")), empty(rs.getString("region")),
        instant(rs.getTimestamp("published_at")), instant(rs.getTimestamp("valid_from")), validUntil, rs.getString("trust_level"),
        rs.getString("content_hash"), status, rs.getObject("created_by", UUID.class), rs.getObject("approved_by", UUID.class),
        instant(rs.getTimestamp("approved_at")), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
        rs.getString("content"));
  }

  private String documentContent(UUID documentId) {
    List<String> chunks = jdbc.query("select content from document_chunks where document_id=? order by chunk_index limit 30",
        (rs, row) -> rs.getString(1), documentId);
    return compact(String.join("\n", chunks), 12_000);
  }
  private List<Map<String, Object>> maps(String raw) {
    try { return json.readValue(Objects.toString(raw, "[]"), new TypeReference<>() { }); }
    catch (Exception ignored) { return List.of(); }
  }
  private void appendBounded(StringBuilder target, String next, int maximum) {
    if (target.length() >= maximum) return;
    if (!target.isEmpty()) target.append("\n\n");
    target.append(next, 0, Math.min(next.length(), Math.max(0, maximum - target.length())));
  }
  private String enumValue(String proposed, String fallback, Set<String> allowed, String label) {
    String value = Objects.toString(proposed, fallback).trim().toUpperCase();
    if (!allowed.contains(value)) throw new IllegalArgumentException(label + "无效");
    return value;
  }
  private String text(String proposed, String fallback) { return Objects.toString(proposed, fallback).trim(); }
  private String compact(String value, int maximum) {
    String safe = Objects.toString(value, "").replaceAll("\\s+", " ").trim();
    return safe.substring(0, Math.min(safe.length(), maximum));
  }
  private String empty(String value) { return Objects.requireNonNullElse(value, ""); }
  private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
  private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
  private Instant value(Instant proposed, Instant fallback) { return proposed == null ? fallback : proposed; }
  private String normalizedDecision(String decision) {
    String value = Objects.toString(decision, "APPROVED").trim().toUpperCase();
    if (!APPROVALS.contains(value)) throw new IllegalArgumentException("审核结果只能为 APPROVED 或 REJECTED");
    return value;
  }
  private String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception error) { throw new IllegalStateException("计算资料指纹失败", error); }
  }

  private record Normalized(UUID sourceDocumentId, String type, String title, String sourceOrganization, String sourceUrl,
      String licenseStatus, String profession, String level, String region, Instant publishedAt, Instant validFrom,
      Instant validUntil, String trustLevel, String content) { }
  public record Upsert(UUID sourceDocumentId, String type, String title, String sourceOrganization, String sourceUrl,
      String licenseStatus, String profession, String level, String region, Instant publishedAt, Instant validFrom,
      Instant validUntil, String trustLevel, String content) { }
  public record Asset(UUID id, UUID knowledgeBaseId, UUID sourceDocumentId, String type, String title,
      String sourceOrganization, String sourceUrl, String licenseStatus, String profession, String level, String region,
      Instant publishedAt, Instant validFrom, Instant validUntil, String trustLevel, String contentHash, String status,
      UUID createdBy, UUID approvedBy, Instant approvedAt, Instant createdAt, Instant updatedAt, String content) {
    public Map<String, Object> publicView() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("id", id); value.put("knowledgeBaseId", knowledgeBaseId);
      value.put("sourceDocumentId", sourceDocumentId == null ? "" : sourceDocumentId);
      value.put("type", type); value.put("title", title); value.put("sourceOrganization", sourceOrganization);
      value.put("sourceUrl", sourceUrl); value.put("licenseStatus", licenseStatus); value.put("profession", profession);
      value.put("level", level); value.put("region", region); value.put("publishedAt", publishedAt == null ? "" : publishedAt);
      value.put("validFrom", validFrom == null ? "" : validFrom); value.put("validUntil", validUntil == null ? "" : validUntil);
      value.put("trustLevel", trustLevel); value.put("contentHash", contentHash); value.put("status", status);
      value.put("approvedAt", approvedAt == null ? "" : approvedAt); value.put("createdAt", createdAt); value.put("updatedAt", updatedAt);
      return value;
    }
  }
  public record EvidencePack(List<Map<String, Object>> standardSources, String standardText,
      List<Map<String, Object>> contexts) { }
  public record EvidenceSnapshot(int sequence, List<Map<String, Object>> standardSources,
      List<Map<String, Object>> contextAssets, String snapshotHash, Instant createdAt) { }
}
