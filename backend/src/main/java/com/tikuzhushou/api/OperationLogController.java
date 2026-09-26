package com.tikuzhushou.api;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operation-logs")
public class OperationLogController {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseAccessService access;
  public OperationLogController(JdbcTemplate jdbc, KnowledgeBaseAccessService access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  @GetMapping List<Map<String, Object>> list(
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) Integer status) {
    if (!access.admin()) throw new AccessDeniedException("仅管理员可查看全局操作日志");
    int max = Math.max(1, Math.min(limit, 200));
    StringBuilder sql = new StringBuilder("select id,actor,method,path,status,elapsed_ms,remote_ip,request_id,created_at"
        + " from operation_logs where created_at>=?");
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(Instant.now().minusSeconds(30L * 24 * 3600)));
    if (actor != null && !actor.isBlank()) { sql.append(" and actor=?"); args.add(actor.trim()); }
    if (status != null) { sql.append(" and status=?"); args.add(status); }
    sql.append(" order by created_at desc limit ?");
    args.add(max);
    return jdbc.query(sql.toString(), (rs, n) -> Map.of(
        "id", rs.getString("id"),
        "action", rs.getString("method"),
        "username", Objects.requireNonNullElse(rs.getString("actor"), "系统"),
        "path", rs.getString("path"),
        "status", rs.getInt("status"),
        "elapsedMs", rs.getLong("elapsed_ms"),
        "remoteIp", Objects.requireNonNullElse(rs.getString("remote_ip"), ""),
        "requestId", Objects.requireNonNullElse(rs.getString("request_id"), ""),
        "createdAt", rs.getTimestamp("created_at").toInstant()), args.toArray());
  }

  @GetMapping("/page")
  Map<String, Object> page(@RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset,
      @RequestParam(required = false) String actor, @RequestParam(required = false) Integer status,
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    if (!access.admin()) throw new AccessDeniedException("仅管理员可查看全局操作日志");
    java.time.LocalDate now = java.time.LocalDate.now(); java.time.LocalDate earliest = now.minusDays(29);
    java.time.LocalDate begin; java.time.LocalDate end;
    try { begin = from == null || from.isBlank() ? now.minusDays(6) : java.time.LocalDate.parse(from); } catch (Exception ignored) { begin = now.minusDays(6); }
    try { end = to == null || to.isBlank() ? now : java.time.LocalDate.parse(to); } catch (Exception ignored) { end = now; }
    if (begin.isBefore(earliest)) begin = earliest; if (end.isBefore(begin)) end = begin;
    java.time.ZoneId zone = java.time.ZoneId.systemDefault(); StringBuilder where = new StringBuilder(" where created_at>=? and created_at<?"); List<Object> args = new ArrayList<>(); args.add(Timestamp.from(begin.atStartOfDay(zone).toInstant())); args.add(Timestamp.from(end.plusDays(1).atStartOfDay(zone).toInstant()));
    if (actor != null && !actor.isBlank()) { where.append(" and actor=?"); args.add(actor.trim()); } if (status != null) { where.append(" and status=?"); args.add(status); }
    int size = Math.max(1, Math.min(limit, 100)), skip = Math.max(0, offset); Integer total = jdbc.queryForObject("select count(*) from operation_logs" + where, Integer.class, args.toArray()); List<Object> rowArgs = new ArrayList<>(args); rowArgs.add(size); rowArgs.add(skip);
    List<Map<String,Object>> items = jdbc.query("select id,actor,method,path,status,elapsed_ms,remote_ip,request_id,created_at from operation_logs" + where + " order by created_at desc,id desc limit ? offset ?", (rs,n)->Map.of("id",rs.getString("id"),"action",rs.getString("method"),"username",Objects.requireNonNullElse(rs.getString("actor"),"系统"),"path",rs.getString("path"),"status",rs.getInt("status"),"elapsedMs",rs.getLong("elapsed_ms"),"remoteIp",maskIp(rs.getString("remote_ip")),"requestId",Objects.requireNonNullElse(rs.getString("request_id"),""),"createdAt",rs.getTimestamp("created_at").toInstant()), rowArgs.toArray());
    return Map.of("items",items,"total",total==null?0:total,"limit",size,"offset",skip,"hasMore",skip+items.size()<(total==null?0:total));
  }

  private String maskIp(String ip) { if (ip == null || ip.isBlank()) return ""; int point = ip.lastIndexOf('.'); return point > 0 ? ip.substring(0, point + 1) + "*" : "[已脱敏]"; }
}
