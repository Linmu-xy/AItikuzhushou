package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.identity.AppUserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class UserAdminController {
  private final AppUserService users;
  private final JdbcTemplate jdbc;
  private final AdminAuditService audit;

  public UserAdminController(AppUserService users, JdbcTemplate jdbc, AdminAuditService audit) {
    this.users = users; this.jdbc = jdbc; this.audit = audit;
  }

  @GetMapping("/users") List<Map<String, Object>> list() { return users.list(); }

  @PostMapping("/users") void create(@RequestBody CreateUser request) {
    users.create(request.username(), request.password(), request.roles(), request.dailyQuota());
    audit.record(AdminAuditService.USER_CREATE, AdminAuditService.TARGET_USER, null,
        request.username(), Map.of(
            "roles", request.roles() == null ? List.of("EDITOR") : request.roles(),
            "dailyQuota", request.dailyQuota() == null ? 1000 : request.dailyQuota()));
  }

  @PatchMapping("/users/{id}") void update(@PathVariable UUID id, @RequestBody UpdateUser request, Authentication authentication) {
    Map<String, Object> before = userSnapshot(id);
    users.update(id, request.status(), request.roles(), request.dailyQuota(), authentication.getName());
    Map<String, Object> after = userSnapshot(id);
    audit.record(AdminAuditService.USER_UPDATE, AdminAuditService.TARGET_USER, id,
        String.valueOf(before.get("username")), Map.of(
            "changes", List.of(
                change("status", before.get("status"), after.get("status")),
                change("roles", before.get("roles"), after.get("roles")),
                change("dailyQuota", before.get("dailyQuota"), after.get("dailyQuota")))));
  }

  private Map<String, Object> change(String field, Object from, Object to) {
    return Map.of("field", field, "from", from == null ? "" : from, "to", to == null ? "" : to);
  }

  private Map<String, Object> userSnapshot(UUID id) {
    var rows = jdbc.query("select username,status,daily_quota,roles from ("
            + "select u.username,u.status,u.daily_model_quota daily_quota,coalesce(string_agg(r.role,','),'') roles"
            + " from app_users u left join app_user_roles r on r.user_id=u.id where u.id=? group by u.id) t",
        (rs, n) -> Map.of(
            "username", rs.getString("username"),
            "status", rs.getString("status"),
            "dailyQuota", rs.getInt("daily_quota"),
            "roles", List.of(rs.getString("roles").split(",")).stream().filter(s -> !s.isBlank()).toList()),
        id);
    return rows.isEmpty() ? Map.of("username", "", "status", "", "dailyQuota", 0, "roles", List.of()) : rows.getFirst();
  }

  @GetMapping("/overview") Map<String, Object> overview() {
    return Map.of(
        "users", count("select count(*) from app_users"),
        "activeUsers", count("select count(*) from app_users where status='ACTIVE'"),
        "knowledgeBases", count("select count(*) from knowledge_bases where status='ACTIVE'"),
        "documents", count("select count(*) from source_documents"),
        "runningTasks", count("select count(*) from generation_jobs where status in ('QUEUED','RUNNING')") + count("select count(*) from workflow_tasks where status in ('QUEUED','RUNNING')"),
        "todayModelRequests", count("select coalesce(sum(request_count),0) from model_usage_daily where usage_date=current_date")
    );
  }

  private int count(String sql) { Integer value = jdbc.queryForObject(sql, Integer.class); return value == null ? 0 : value; }
  public record CreateUser(String username, String password, List<String> roles, Integer dailyQuota) {}
  public record UpdateUser(String status, List<String> roles, Integer dailyQuota) {}
}
