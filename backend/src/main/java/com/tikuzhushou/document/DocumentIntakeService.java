package com.tikuzhushou.document;

import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentIntakeService {
  private static final long MAX_BYTES = 64L * 1024 * 1024;
  private static final Set<String> TYPES = Set.of("application/pdf", "application/msword",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-powerpoint",
      "application/vnd.openxmlformats-officedocument.presentationml.presentation", "image/jpeg", "image/png",
      "image/gif", "image/webp");
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseAccessService access;
  private final ObjectStorageService storage;
  private final Map<UUID, DocumentReceipt> cache = new ConcurrentHashMap<>();

  public DocumentIntakeService(JdbcTemplate jdbc, KnowledgeBaseAccessService access, ObjectStorageService storage) {
    this.jdbc = jdbc; this.access = access; this.storage = storage;
  }

  public DocumentReceipt store(UUID knowledgeBaseId, MultipartFile file) throws IOException {
    if (file.isEmpty() || file.getSize() > MAX_BYTES) throw new IllegalArgumentException("文件不能为空且不得超过 64MB");
    String name = Objects.requireNonNullElse(file.getOriginalFilename(), "document")
        .replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
    String type = Optional.ofNullable(file.getContentType()).filter(TYPES::contains).orElseGet(() -> extension(name));
    if (type == null) throw new IllegalArgumentException("不支持的文件格式");
    ensure(knowledgeBaseId);
    String hash = sha256(file);
    var duplicate = jdbc.query("select id,original_name from source_documents where knowledge_base_id=? and content_sha256=? order by created_at desc limit 1",
        (rs, row) -> Map.of("id", rs.getString(1), "name", rs.getString(2)), knowledgeBaseId, hash).stream().findFirst();
    if (duplicate.isPresent()) throw new IllegalArgumentException("DUPLICATE_DOCUMENT：相同内容已存在（"
        + duplicate.get().get("name") + "，文档 " + duplicate.get().get("id")
        + "）。请在资料列表查看；若解析失败或不完整，可直接点击“重新解析”。");
    quota(knowledgeBaseId, file.getSize());

    UUID id = UUID.randomUUID(); String key = id + "_" + name; String path;
    try (InputStream input = file.getInputStream()) { path = storage.put(key, input, file.getSize(), type); }
    catch (Exception error) { throw new IOException("对象存储写入失败", error); }
    Instant now = Instant.now();
    var receipt = new DocumentReceipt(id, knowledgeBaseId, name, type, file.getSize(), path, hash, now);
    jdbc.update("insert into source_documents(id,knowledge_base_id,original_name,media_type,storage_key,size_bytes,status,parse_mode,content_sha256,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
        id, knowledgeBaseId, name, type, path, file.getSize(), "UPLOADED", "AUTO", hash,
        java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
    cache.put(id, receipt); return receipt;
  }

  public DocumentReceipt get(UUID id) {
    var hit = cache.get(id);
    if (hit != null) { access.assertKnowledgeBase(hit.knowledgeBaseId()); return hit; }
    var rows = jdbc.query("select id,knowledge_base_id,original_name,media_type,size_bytes,storage_key,content_sha256,created_at from source_documents where id=?",
        (rs, row) -> new DocumentReceipt(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
            rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7),
            rs.getTimestamp(8).toInstant()), id);
    if (rows.isEmpty()) throw new IllegalArgumentException("文档不存在");
    access.assertKnowledgeBase(rows.getFirst().knowledgeBaseId()); cache.put(id, rows.getFirst()); return rows.getFirst();
  }

  /** Older imported documents may predate upload hashing; recover the fingerprint from the stored original. */
  public String ensureSha256(UUID id) {
    DocumentReceipt receipt = get(id);
    if (receipt.sha256() != null && !receipt.sha256().isBlank()) return receipt.sha256();
    Path materialized = null;
    try {
      materialized = storage.materialize(receipt.storagePath(), ".bin");
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = java.nio.file.Files.newInputStream(materialized)) {
        byte[] buffer = new byte[8192]; int read;
        while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
      }
      String hash = HexFormat.of().formatHex(digest.digest());
      jdbc.update("update source_documents set content_sha256=? where id=? and (content_sha256 is null or trim(content_sha256)='')", hash, id);
      cache.put(id, new DocumentReceipt(receipt.id(), receipt.knowledgeBaseId(), receipt.name(),
          receipt.mediaType(), receipt.size(), receipt.storagePath(), hash, receipt.createdAt()));
      return hash;
    } catch (Exception error) {
      throw new IllegalStateException("旧资料原文件无法校验，请重新上传：" + receipt.name(), error);
    } finally {
      if (materialized != null) {
        try { storage.cleanupMaterialized(materialized); }
        catch (IOException error) { throw new IllegalStateException("临时资料清理失败", error); }
      }
    }
  }

  private String sha256(MultipartFile file) throws IOException {
    try (InputStream input = file.getInputStream()) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[8192]; int read;
      while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  private void ensure(UUID id) {
    Integer count = jdbc.queryForObject("select count(*) from knowledge_bases where id=?", Integer.class, id);
    if (count != null && count > 0) { access.assertKnowledgeBase(id); return; }
    throw new IllegalArgumentException("知识库不存在");
  }
  private void quota(UUID id, long size) {
    Long used = jdbc.queryForObject("select coalesce(sum(size_bytes),0) from source_documents where knowledge_base_id=?", Long.class, id);
    Long max = jdbc.queryForObject("select storage_quota_bytes from knowledge_bases where id=?", Long.class, id);
    if ((used == null ? 0 : used) + size > (max == null ? 10L * 1024 * 1024 * 1024 : max)) throw new IllegalArgumentException("知识库存储配额不足");
    UUID owner = jdbc.queryForObject("select owner_id from knowledge_bases where id=?", UUID.class, id);
    Long ownerUsed = jdbc.queryForObject("select coalesce(sum(d.size_bytes),0) from source_documents d join knowledge_bases k on k.id=d.knowledge_base_id where k.owner_id=?", Long.class, owner);
    Long ownerMax = jdbc.queryForObject("select storage_quota_bytes from app_users where id=?", Long.class, owner);
    if ((ownerUsed == null ? 0 : ownerUsed) + size > (ownerMax == null ? 10L * 1024 * 1024 * 1024 : ownerMax)) {
      throw new IllegalArgumentException("个人存储配额不足（账号总容量已达上限）");
    }
  }
  private String extension(String name) {
    String value = name.toLowerCase();
    if (value.endsWith(".pdf")) return "application/pdf";
    if (value.endsWith(".doc")) return "application/msword";
    if (value.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if (value.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
    if (value.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    if (value.endsWith(".png")) return "image/png";
    if (value.endsWith(".jpg") || value.endsWith(".jpeg")) return "image/jpeg";
    return null;
  }
  public record DocumentReceipt(UUID id, UUID knowledgeBaseId, String name, String mediaType, long size,
      String storagePath, String sha256, Instant createdAt) { }
}
