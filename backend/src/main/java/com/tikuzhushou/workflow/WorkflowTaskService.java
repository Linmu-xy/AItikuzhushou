package com.tikuzhushou.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class WorkflowTaskService {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseAccessService access;
  private final ObjectMapper json;

  public WorkflowTaskService(JdbcTemplate jdbc, KnowledgeBaseAccessService access, ObjectMapper json) {
    this.jdbc = jdbc;
    this.access = access;
    this.json = json;
  }

  public Created create(String type, UUID resourceId, String idempotencyKey, String initialMessage) {
    UUID owner = access.currentUserId();
    String key = normalizeKey(idempotencyKey);
    List<Task> existing = findByKey(owner, key);
    if (!existing.isEmpty()) return new Created(existing.getFirst(), false);
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    try {
      jdbc.update("insert into workflow_tasks(id,owner_id,task_type,resource_id,status,stage_code,progress,processed_items,total_items,status_message,idempotency_key,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id, owner, type, resourceId, "QUEUED", "QUEUED", 0, 0, 0, initialMessage, key,
          java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
    } catch (DuplicateKeyException race) {
      return new Created(findByKey(owner, key).getFirst(), false);
    }
    return new Created(get(id), true);
  }

  public Task update(UUID id, String status, String stageCode, int progress, int processed, int total,
      String message, String errorCode, String errorMessage) {
    jdbc.update("update workflow_tasks set status=?,stage_code=?,progress=?,processed_items=?,total_items=?,status_message=?,error_code=?,error_message=?,updated_at=? where id=?",
        status, stageCode, clamp(progress), Math.max(0, processed), Math.max(0, total), limit(message, 500),
        errorCode, limit(errorMessage, 2000), java.sql.Timestamp.from(Instant.now()), id);
    return systemGet(id);
  }

  public Task result(UUID id, Map<String, Object> result) {
    try {
      jdbc.update("update workflow_tasks set result_json=?,updated_at=? where id=?",
          json.writeValueAsString(result), java.sql.Timestamp.from(Instant.now()), id);
      return systemGet(id);
    } catch (Exception error) {
      throw new IllegalStateException("保存任务结果失败", error);
    }
  }

  public Task get(UUID id) {
    Task task = systemGet(id);
    if (!access.admin() && !task.ownerId().equals(access.currentUserId())) throw new AccessDeniedException("无权访问其他用户的任务");
    return task;
  }

  public Task systemGet(UUID id) {
    List<Task> rows = jdbc.query("select id,owner_id,task_type,resource_id,status,stage_code,progress,processed_items,total_items,status_message,error_code,error_message,idempotency_key,result_json,created_at,updated_at from workflow_tasks where id=?",
        (rs, n) -> map(rs), id);
    if (rows.isEmpty()) throw new IllegalArgumentException("工作流任务不存在");
    return rows.getFirst();
  }

  public boolean claim(UUID id) {
    return claim(id, "PARSE_QUEUED", "后台 Worker 已领取任务");
  }

  public boolean claim(UUID id, String stageCode, String message) {
    return jdbc.update("update workflow_tasks set status='RUNNING',stage_code=?,progress=1,status_message=?,updated_at=? where id=? and status='QUEUED'",
        stageCode, limit(message, 500), java.sql.Timestamp.from(Instant.now()), id) == 1;
  }

  public boolean cancelled(UUID id) {
    return "CANCELLED".equals(systemGet(id).status());
  }

  public Task cancel(UUID id) {
    Task task = get(id);
    if ("SUCCEEDED".equals(task.status()) || "FAILED".equals(task.status())) {
      throw new IllegalArgumentException("已结束的任务不能取消");
    }
    jdbc.update("update workflow_tasks set status='CANCELLED',stage_code='CANCELLED',status_message='任务已由用户取消',updated_at=? where id=? and status in ('QUEUED','RUNNING')",
        java.sql.Timestamp.from(Instant.now()), id);
    return get(id);
  }

  public List<Task> list(int limit) {
    int safe = Math.max(1, Math.min(limit, 100));
    if (access.admin()) return jdbc.query("select id,owner_id,task_type,resource_id,status,stage_code,progress,processed_items,total_items,status_message,error_code,error_message,idempotency_key,result_json,created_at,updated_at from workflow_tasks order by updated_at desc limit ?",
        (rs, n) -> map(rs), safe);
    return jdbc.query("select id,owner_id,task_type,resource_id,status,stage_code,progress,processed_items,total_items,status_message,error_code,error_message,idempotency_key,result_json,created_at,updated_at from workflow_tasks where owner_id=? order by updated_at desc limit ?",
        (rs, n) -> map(rs), access.currentUserId(), safe);
  }

  public List<UUID> recoverable() {
    jdbc.update("update workflow_tasks set status='QUEUED',stage_code='QUEUED',status_message='服务恢复后重新排队',updated_at=? where status='RUNNING' and task_type='DOCUMENT_PARSE'",
        java.sql.Timestamp.from(Instant.now()));
    return jdbc.query("select id from workflow_tasks where status in ('QUEUED','RUNNING') and task_type='DOCUMENT_PARSE'",
        (rs, n) -> UUID.fromString(rs.getString(1)));
  }

  public List<UUID> recoverable(String taskType) {
    jdbc.update("update workflow_tasks set status='QUEUED',stage_code='QUEUED',status_message='服务恢复后重新排队',updated_at=? where status='RUNNING' and task_type=?",
        java.sql.Timestamp.from(Instant.now()), taskType);
    return jdbc.query("select id from workflow_tasks where status='QUEUED' and task_type=?",
        (rs, n) -> UUID.fromString(rs.getString(1)), taskType);
  }

  private List<Task> findByKey(UUID owner, String key) {
    return jdbc.query("select id,owner_id,task_type,resource_id,status,stage_code,progress,processed_items,total_items,status_message,error_code,error_message,idempotency_key,result_json,created_at,updated_at from workflow_tasks where owner_id=? and idempotency_key=?",
        (rs, n) -> map(rs), owner, key);
  }

  private Task map(java.sql.ResultSet rs) throws java.sql.SQLException {
    Map<String, Object> result = Map.of();
    String resultJson = rs.getString("result_json");
    if (resultJson != null && !resultJson.isBlank()) {
      try { result = json.readValue(resultJson, new TypeReference<>() { }); }
      catch (Exception ignored) { result = Map.of("statusCode", "INVALID_TASK_RESULT"); }
    }
    return new Task(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("owner_id")),
        rs.getString("task_type"), rs.getObject("resource_id", UUID.class), rs.getString("status"),
        rs.getString("stage_code"), rs.getInt("progress"), rs.getInt("processed_items"),
        rs.getInt("total_items"), Objects.toString(rs.getString("status_message"), ""),
        rs.getString("error_code"), rs.getString("error_message"), rs.getString("idempotency_key"), result,
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
  }

  private String normalizeKey(String value) {
    String key = Objects.toString(value, "").trim();
    if (key.isBlank()) key = UUID.randomUUID().toString();
    if (!key.matches("[A-Za-z0-9._:-]{8,120}")) throw new IllegalArgumentException("幂等键格式无效");
    return key;
  }
  private int clamp(int value) { return Math.max(0, Math.min(100, value)); }
  private String limit(String value, int max) {
    if (value == null) return null;
    return value.substring(0, Math.min(value.length(), max));
  }

  public record Created(Task task, boolean created) { }
  public record Task(UUID id, UUID ownerId, String taskType, UUID resourceId, String status,
      String stageCode, int progress, int processedItems, int totalItems, String statusMessage,
      String errorCode, String errorMessage, String idempotencyKey, Map<String, Object> result,
      Instant createdAt, Instant updatedAt) { }
}
