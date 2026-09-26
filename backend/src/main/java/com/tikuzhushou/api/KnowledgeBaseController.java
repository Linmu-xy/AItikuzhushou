package com.tikuzhushou.api;
import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseAccessService access;
  private final AdminAuditService audit;
  public KnowledgeBaseController(JdbcTemplate jdbc, KnowledgeBaseAccessService access, AdminAuditService audit) {
    this.jdbc = jdbc; this.access = access; this.audit = audit;
  }

  @GetMapping List<Map<String, Object>> list() {
    String sql = "select k.id,k.name,k.description,k.status,k.storage_quota_bytes,k.created_at,coalesce(sum(d.size_bytes),0) used_bytes"
        + " from knowledge_bases k left join source_documents d on d.knowledge_base_id=k.id "
        + (access.admin() ? "where k.status='ACTIVE' " : "where k.owner_id=? and k.status='ACTIVE' ")
        + "group by k.id,k.name,k.description,k.status,k.storage_quota_bytes,k.created_at order by k.created_at desc";
    return access.admin() ? jdbc.query(sql, (rs, n) -> view(rs))
        : jdbc.query(sql, (rs, n) -> view(rs), access.currentUserId());
  }

  @GetMapping("/{id}/documents")
  List<Map<String, Object>> documents(@PathVariable UUID id) {
    access.assertKnowledgeBase(id);
    return jdbc.query("select id,original_name,media_type,size_bytes,status,created_at from source_documents"
        + " where knowledge_base_id=? order by created_at desc", (rs, n) -> Map.of(
        "id", rs.getString("id"), "originalFilename", rs.getString("original_name"),
        "mediaType", Objects.requireNonNullElse(rs.getString("media_type"), ""),
        "sizeBytes", rs.getLong("size_bytes"), "status", rs.getString("status"),
        "createdAt", rs.getTimestamp("created_at").toInstant()), id);
  }

  @PostMapping Map<String, Object> create(@RequestBody Upsert request) {
    if (request.name() == null || request.name().isBlank()) throw new IllegalArgumentException("知识库名称不能为空");
    UUID id = UUID.randomUUID();
    long quota = request.quotaBytes() == null ? 10L * 1024 * 1024 * 1024 : Math.max(1, request.quotaBytes());
    java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
    jdbc.update("insert into knowledge_bases(id,name,description,owner_id,status,storage_quota_bytes,created_at,updated_at) values(?,?,?,?,?,?,?,?)",
        id, request.name().trim(), Objects.requireNonNullElse(request.description(), ""),
        access.currentUserId(), "ACTIVE", quota, now, now);
    audit.record(AdminAuditService.KB_CREATE, AdminAuditService.TARGET_KB, id, request.name().trim(),
        Map.of("quotaBytes", quota));
    return Map.of("id", id, "status", "ACTIVE");
  }

  @PutMapping("/{id}") Map<String, Object> update(@PathVariable UUID id, @RequestBody Upsert request) {
    access.assertKnowledgeBase(id);
    if (request.name() == null || request.name().isBlank()) throw new IllegalArgumentException("知识库名称不能为空");
    if (request.quotaBytes() != null && !access.admin()) throw new IllegalArgumentException("仅管理员可调整配额");
    var before = snapshot(id);
    if (request.quotaBytes() != null) {
      jdbc.update("update knowledge_bases set name=?,description=?,storage_quota_bytes=?,updated_at=? where id=?",
          request.name().trim(), Objects.requireNonNullElse(request.description(), ""),
          request.quotaBytes(), java.sql.Timestamp.from(Instant.now()), id);
    } else {
      jdbc.update("update knowledge_bases set name=?,description=?,updated_at=? where id=?",
          request.name().trim(), Objects.requireNonNullElse(request.description(), ""),
          java.sql.Timestamp.from(Instant.now()), id);
    }
    var after = snapshot(id);
    String name = String.valueOf(before.getOrDefault("name", ""));
    boolean quotaChanged = request.quotaBytes() != null
        && !Objects.equals(before.get("quotaBytes"), after.get("quotaBytes"));
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("changes", List.of(
        change("name", before.get("name"), after.get("name")),
        change("description", before.get("description"), after.get("description")),
        change("quotaBytes", before.get("quotaBytes"), after.get("quotaBytes"))));
    if (access.admin() && quotaChanged) {
      audit.record(AdminAuditService.KB_QUOTA_UPDATE, AdminAuditService.TARGET_KB, id, name, detail);
    } else {
      audit.record(AdminAuditService.KB_UPDATE, AdminAuditService.TARGET_KB, id, name, detail);
    }
    return Map.of("id", id, "status", "UPDATED");
  }

  @DeleteMapping("/{id}") Map<String, Object> archive(@PathVariable UUID id) {
    access.assertKnowledgeBase(id);
    var before = snapshot(id);
    UUID recycleId = UUID.randomUUID(); Instant now = Instant.now(); Instant until = now.plus(30, ChronoUnit.DAYS);
    String snapshot = "{\"knowledgeBaseId\":\"" + id + "\",\"status\":\"" + before.getOrDefault("status", "ACTIVE") + "\"}";
    jdbc.update("insert into recycle_bin_items(id,owner_id,resource_type,resource_id,snapshot_json,deleted_at,recoverable_until) values(?,?,?,?,?,?,?)",
        recycleId, UUID.fromString(String.valueOf(before.get("ownerId"))), "KNOWLEDGE_BASE", id, snapshot,
        java.sql.Timestamp.from(now), java.sql.Timestamp.from(until));
    jdbc.update("update knowledge_bases set status='RECYCLED',updated_at=? where id=?",
        java.sql.Timestamp.from(Instant.now()), id);
    audit.record(AdminAuditService.KB_ARCHIVE, AdminAuditService.TARGET_KB, id,
        String.valueOf(before.getOrDefault("name", "")), Map.of("ownerId",
            before.get("ownerId") == null ? "" : before.get("ownerId")));
    return Map.of("id", id, "status", "RECYCLED", "recycleId", recycleId, "recoverableUntil", until);
  }

  @GetMapping("/recycle-bin") List<Map<String, Object>> recycleBin() {
    String sql = "select r.id,r.resource_type,r.resource_id,r.deleted_at,r.recoverable_until,k.name from recycle_bin_items r left join knowledge_bases k on k.id=r.resource_id where r.restored_at is null and r.recoverable_until>now() "
        + (access.admin() ? "" : "and r.owner_id=? ") + "order by r.deleted_at desc";
    return access.admin() ? jdbc.query(sql, (rs, n) -> recycleView(rs)) : jdbc.query(sql, (rs, n) -> recycleView(rs), access.currentUserId());
  }

  @PostMapping("/recycle-bin/{recycleId}/restore") Map<String, Object> restore(@PathVariable UUID recycleId) {
    var rows = jdbc.query("select owner_id,resource_type,resource_id,recoverable_until from recycle_bin_items where id=? and restored_at is null", (rs, n) -> Map.of(
        "owner", rs.getObject("owner_id", UUID.class), "type", rs.getString("resource_type"), "resource", rs.getObject("resource_id", UUID.class), "until", rs.getTimestamp("recoverable_until").toInstant()), recycleId);
    if (rows.isEmpty()) throw new IllegalArgumentException("回收站项目不存在或已恢复"); var row = rows.getFirst();
    if (!access.admin() && !access.currentUserId().equals(row.get("owner"))) throw new org.springframework.security.access.AccessDeniedException("无权恢复其他用户的数据");
    if (Instant.now().isAfter((Instant) row.get("until"))) throw new IllegalArgumentException("已超过 30 天恢复期限");
    if (!"KNOWLEDGE_BASE".equals(row.get("type"))) throw new IllegalArgumentException("暂不支持该资源类型恢复");
    jdbc.update("update knowledge_bases set status='ACTIVE',updated_at=? where id=?", java.sql.Timestamp.from(Instant.now()), row.get("resource"));
    jdbc.update("update recycle_bin_items set restored_at=? where id=?", java.sql.Timestamp.from(Instant.now()), recycleId);
    return Map.of("id", row.get("resource"), "status", "ACTIVE");
  }

  @PostMapping("/{id}/shares") Map<String, Object> createShare(@PathVariable UUID id, @RequestParam(defaultValue = "24") int hours) {
    access.assertKnowledgeBase(id); int safeHours = Math.max(1, Math.min(hours, 24 * 30)); Instant now = Instant.now(); Instant expires = now.plus(safeHours, ChronoUnit.HOURS);
    byte[] bytes = new byte[24]; new SecureRandom().nextBytes(bytes); String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    UUID shareId = UUID.randomUUID(); jdbc.update("insert into knowledge_base_shares(id,knowledge_base_id,owner_id,share_token,expires_at,created_at) values(?,?,?,?,?,?)", shareId, id, access.currentUserId(), token, java.sql.Timestamp.from(expires), java.sql.Timestamp.from(now));
    audit.record("KB_SHARE_CREATE", AdminAuditService.TARGET_KB, id, null, Map.of("shareId", shareId, "expiresAt", expires.toString()));
    return Map.of("id", shareId, "token", token, "expiresAt", expires, "passwordRequired", false);
  }

  @DeleteMapping("/shares/{shareId}") Map<String, Object> revokeShare(@PathVariable UUID shareId) {
    var rows = jdbc.query("select owner_id,knowledge_base_id from knowledge_base_shares where id=? and revoked_at is null", (rs,n) -> Map.of("owner",rs.getObject(1,UUID.class),"kb",rs.getObject(2,UUID.class)), shareId);
    if (rows.isEmpty()) throw new IllegalArgumentException("分享链接不存在或已撤销"); var row=rows.getFirst();
    if (!access.admin() && !access.currentUserId().equals(row.get("owner"))) throw new org.springframework.security.access.AccessDeniedException("无权撤销其他用户的分享");
    jdbc.update("update knowledge_base_shares set revoked_at=? where id=?", java.sql.Timestamp.from(Instant.now()), shareId); return Map.of("id", shareId, "status", "REVOKED");
  }

  @GetMapping("/shared/{token}") Map<String, Object> shared(@PathVariable String token) {
    List<Map<String, Object>> rows=jdbc.query("select k.id,k.name,k.description,s.expires_at from knowledge_base_shares s join knowledge_bases k on k.id=s.knowledge_base_id where s.share_token=? and s.revoked_at is null and s.expires_at>now() and k.status='ACTIVE'", (rs,n)-> { Map<String,Object> value = new LinkedHashMap<>(); value.put("id", rs.getString(1)); value.put("name",rs.getString(2)); value.put("description",Objects.requireNonNullElse(rs.getString(3),"")); value.put("expiresAt",rs.getTimestamp(4).toInstant()); return value; }, token);
    if(rows.isEmpty()) throw new IllegalArgumentException("分享链接无效、已撤销或已过期"); return rows.getFirst();
  }

  private Map<String, Object> change(String field, Object from, Object to) {
    return Map.of("field", field, "from", from == null ? "" : from, "to", to == null ? "" : to);
  }

  private Map<String, Object> snapshot(UUID id) {
    var rows = jdbc.query("select name,description,status,storage_quota_bytes,owner_id from knowledge_bases where id=?",
        (rs, n) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("name", rs.getString("name"));
          m.put("description", Objects.requireNonNullElse(rs.getString("description"), ""));
          m.put("status", rs.getString("status"));
          m.put("quotaBytes", rs.getLong("storage_quota_bytes"));
          m.put("ownerId", rs.getString("owner_id"));
          return m;
        }, id);
    return rows.isEmpty() ? new LinkedHashMap<>() : rows.getFirst();
  }

  private Map<String, Object> view(java.sql.ResultSet rs) throws java.sql.SQLException {
    return Map.of("id", rs.getString("id"), "name", rs.getString("name"),
        "description", Objects.requireNonNullElse(rs.getString("description"), ""),
        "status", rs.getString("status"), "quotaBytes", rs.getLong("storage_quota_bytes"),
        "usedBytes", rs.getLong("used_bytes"), "createdAt", rs.getTimestamp("created_at").toInstant());
  }
  private Map<String, Object> recycleView(java.sql.ResultSet rs) throws java.sql.SQLException { return Map.of("id",rs.getString("id"),"resourceType",rs.getString("resource_type"),"resourceId",rs.getString("resource_id"),"name",Objects.requireNonNullElse(rs.getString("name"),"已删除资源"),"deletedAt",rs.getTimestamp("deleted_at").toInstant(),"recoverableUntil",rs.getTimestamp("recoverable_until").toInstant()); }
  public record Upsert(String name, String description, Long quotaBytes) {}
}
