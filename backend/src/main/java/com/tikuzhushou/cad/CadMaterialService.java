package com.tikuzhushou.cad;

import com.tikuzhushou.document.ObjectStorageService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Owns isolated model/drawing material storage for the material exam mode. */
@Service
public class CadMaterialService {
  private static final Set<String> FORMATS = Set.of("STEP", "STP", "X_T", "PRT", "DWG", "DXF", "PDF", "STL", "OBJ");

  private final JdbcTemplate jdbc;
  private final KnowledgeBaseAccessService access;
  private final ObjectStorageService storage;
  private final long maxFileSize;

  public CadMaterialService(JdbcTemplate jdbc, KnowledgeBaseAccessService access, ObjectStorageService storage,
      @Value("${app.cad.max-file-size:67108864}") long maxFileSize) {
    this.jdbc = jdbc;
    this.access = access;
    this.storage = storage;
    this.maxFileSize = Math.max(1L, maxFileSize);
  }

  public Material upload(UUID knowledgeBaseId, MultipartFile file) throws Exception {
    access.assertKnowledgeBase(knowledgeBaseId);
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("模型或工程图文件不能为空");
    if (file.getSize() > maxFileSize) throw new IllegalArgumentException("模型或工程图文件超过大小限制");
    String originalName = safeName(Objects.requireNonNullElse(file.getOriginalFilename(), "cad-material"));
    String format = formatOf(originalName);
    String mediaType = Optional.ofNullable(file.getContentType()).filter(value -> !value.isBlank())
        .orElseGet(() -> mediaType(format));
    String hash = sha256(file);
    List<Material> duplicates = jdbc.query(
        "select id,knowledge_base_id,original_name,media_type,format,storage_key,size_bytes,content_sha256,status,created_by,created_at,updated_at from cad_materials where knowledge_base_id=? and content_sha256=?",
        (rs, row) -> map(rs), knowledgeBaseId, hash);
    if (!duplicates.isEmpty()) throw new IllegalArgumentException("DUPLICATE_CAD_MATERIAL：相同模型或工程图已存在");
    checkQuota(knowledgeBaseId, file.getSize());

    UUID id = UUID.randomUUID();
    String storageKey = "cad_" + id + "_" + originalName;
    String stored;
    try (InputStream input = file.getInputStream()) {
      stored = storage.put(storageKey, input, file.getSize(), mediaType);
    }
    Instant now = Instant.now();
    UUID actor = access.currentUserId();
    jdbc.update("insert into cad_materials(id,knowledge_base_id,original_name,media_type,format,storage_key,size_bytes,content_sha256,status,created_by,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
        id, knowledgeBaseId, originalName, mediaType, format, stored, file.getSize(), hash, "UPLOADED", actor,
        Timestamp.from(now), Timestamp.from(now));
    return get(id);
  }

  public List<Material> list(UUID knowledgeBaseId) {
    access.assertKnowledgeBase(knowledgeBaseId);
    return jdbc.query(
        "select id,knowledge_base_id,original_name,media_type,format,storage_key,size_bytes,content_sha256,status,created_by,created_at,updated_at from cad_materials where knowledge_base_id=? order by created_at desc",
        (rs, row) -> map(rs), knowledgeBaseId);
  }

  public Material get(UUID id) {
    List<Material> rows = jdbc.query(
        "select id,knowledge_base_id,original_name,media_type,format,storage_key,size_bytes,content_sha256,status,created_by,created_at,updated_at from cad_materials where id=?",
        (rs, row) -> map(rs), id);
    if (rows.isEmpty()) throw new IllegalArgumentException("模型或工程图不存在");
    Material material = rows.getFirst();
    access.assertKnowledgeBase(material.knowledgeBaseId());
    return material;
  }

  public Path materialize(Material material) throws Exception {
    return storage.materialize(material.storageKey(), "." + material.format().toLowerCase(Locale.ROOT));
  }

  public void updateStatus(UUID id, String status) {
    jdbc.update("update cad_materials set status=?,updated_at=? where id=?", status,
        Timestamp.from(Instant.now()), id);
  }

  private void checkQuota(UUID knowledgeBaseId, long incoming) {
    Long usedDocuments = jdbc.queryForObject("select coalesce(sum(size_bytes),0) from source_documents where knowledge_base_id=?", Long.class, knowledgeBaseId);
    Long usedCad = jdbc.queryForObject("select coalesce(sum(size_bytes),0) from cad_materials where knowledge_base_id=?", Long.class, knowledgeBaseId);
    Long max = jdbc.queryForObject("select storage_quota_bytes from knowledge_bases where id=?", Long.class, knowledgeBaseId);
    long used = (usedDocuments == null ? 0L : usedDocuments) + (usedCad == null ? 0L : usedCad);
    if (max != null && used + incoming > max) throw new IllegalArgumentException("知识库存储配额不足");
  }

  private String sha256(MultipartFile file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = file.getInputStream()) {
      byte[] buffer = new byte[1024 * 1024];
      int read;
      while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private String safeName(String value) {
    String name = value.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
    return name.isBlank() ? "cad-material" : name.substring(0, Math.min(name.length(), 220));
  }

  private String formatOf(String name) {
    String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) : "";
    if (!FORMATS.contains(extension)) throw new IllegalArgumentException("暂不支持该模型或工程图格式");
    return extension;
  }

  private String mediaType(String format) {
    return switch (format) {
      case "PDF" -> "application/pdf";
      case "DWG" -> "application/acad";
      case "DXF" -> "application/dxf";
      case "STL" -> "model/stl";
      case "OBJ" -> "text/plain";
      default -> "application/octet-stream";
    };
  }

  private Material map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Material(rs.getObject("id", UUID.class), rs.getObject("knowledge_base_id", UUID.class),
        rs.getString("original_name"), rs.getString("media_type"), rs.getString("format"),
        rs.getString("storage_key"), rs.getLong("size_bytes"), rs.getString("content_sha256"),
        rs.getString("status"), rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  public record Material(UUID id, UUID knowledgeBaseId, String originalName, String mediaType, String format,
      String storageKey, long sizeBytes, String sha256, String status, UUID createdBy, Instant createdAt,
      Instant updatedAt) { }
}
