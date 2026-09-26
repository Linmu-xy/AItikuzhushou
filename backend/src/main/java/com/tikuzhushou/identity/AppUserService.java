package com.tikuzhushou.identity;

import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserService implements UserDetailsService {
  private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "EDITOR", "VIEWER");
  private final JdbcTemplate jdbc;
  private final PasswordEncoder encoder;
  private final String bootstrapPassword;

  public AppUserService(JdbcTemplate jdbc, PasswordEncoder encoder,
      @Value("${app.security.bootstrap-admin-password}") String bootstrapPassword) {
    this.jdbc = jdbc;
    this.encoder = encoder;
    this.bootstrapPassword = bootstrapPassword;
  }

  @PostConstruct
  void bootstrap() {
    Integer count = jdbc.queryForObject("select count(*) from app_users where username='admin'", Integer.class);
    if (count != null && count > 0) return;
    UUID id = UUID.nameUUIDFromBytes("admin".getBytes());
    jdbc.update("insert into app_users(id,username,password_hash,status,created_at) values(?,?,?,?,?)",
        id, "admin", encoder.encode(bootstrapPassword), "ACTIVE", Timestamp.from(Instant.now()));
    jdbc.update("insert into app_user_roles(user_id,role) values(?,?)", id, "ADMIN");
  }

  @Override
  public UserDetails loadUserByUsername(String username) {
    var rows = jdbc.query("select id,username,password_hash,status from app_users where username=? or lower(email)=lower(?)",
        (rs, n) -> new Row(UUID.fromString(rs.getString("id")), rs.getString("username"),
            rs.getString("password_hash"), rs.getString("status")), username, username);
    if (rows.isEmpty()) throw new UsernameNotFoundException(username);
    Row row = rows.getFirst();
    List<String> roles = roles(row.id());
    return User.withUsername(row.username()).password(row.hash())
        .authorities(roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList())
        .disabled(!"ACTIVE".equals(row.status())).build();
  }

  public UUID currentId(String username) { return jdbc.queryForObject("select id from app_users where username=? or lower(email)=lower(?)", UUID.class, username, username); }

  @Transactional
  public Map<String, Object> profile(String username) {
    UUID id = currentId(username);
    ensurePersonalWorkspace(id, username);
    return jdbc.queryForObject("select username,email,email_verified,status,daily_model_quota,storage_quota_bytes,session_revision,created_at from app_users where id=?", (rs, n) -> {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", id.toString()); result.put("username", rs.getString("username")); result.put("email", Objects.toString(rs.getString("email"), ""));
      result.put("emailVerified", rs.getBoolean("email_verified")); result.put("status", rs.getString("status"));
      result.put("dailyQuota", rs.getInt("daily_model_quota")); result.put("storageQuotaBytes", rs.getLong("storage_quota_bytes")); result.put("sessionRevision", rs.getInt("session_revision")); result.put("createdAt", rs.getTimestamp("created_at").toInstant()); result.put("roles", roles(id)); return result;
    }, id);
  }

  public List<Map<String, Object>> list() {
    return jdbc.query("select u.id,u.username,u.email,u.email_verified,u.status,u.daily_model_quota,u.created_at,coalesce(string_agg(r.role,','),'') roles "
            + "from app_users u left join app_user_roles r on r.user_id=u.id "
            + "group by u.id,u.username,u.status,u.daily_model_quota,u.created_at order by u.created_at",
        (rs, n) -> Map.of("id", rs.getString("id"), "username", rs.getString("username"), "email", Objects.toString(rs.getString("email"), ""), "emailVerified", rs.getBoolean("email_verified"), "status", rs.getString("status"),
            "roles", rs.getString("roles"), "dailyQuota", rs.getInt("daily_model_quota"), "createdAt", rs.getTimestamp("created_at").toInstant()));
  }

  public void register(String username, String password) { create(username, password, List.of("EDITOR"), 1000); }

  @Transactional
  public void registerEmail(String username, String email, String password) {
    String normalizedUsername = normalizeUsername(username);
    validatePassword(password);
    String normalized = EmailVerificationService.normalizedEmail(email);
    UUID id = UUID.randomUUID();
    try {
      jdbc.update("insert into app_users(id,username,email,email_verified,email_verified_at,password_hash,status,daily_model_quota,created_at) values(?,?,?,?,?,?,?,?,?)",
          id, normalizedUsername, normalized, true, Timestamp.from(Instant.now()), encoder.encode(password), "ACTIVE", 1000, Timestamp.from(Instant.now()));
      jdbc.update("insert into app_user_roles(user_id,role) values(?,?)", id, "EDITOR");
      ensurePersonalWorkspace(id, normalizedUsername);
    } catch (Exception error) { throw new IllegalArgumentException("用户名或邮箱已注册"); }
  }

  public UserDetails loadUserByEmail(String email) { return loadUserByUsername(email); }

  @Transactional
  public void create(String username, String password, List<String> roles, Integer dailyQuota) {
    String normalizedUsername = normalizeUsername(username);
    validatePassword(password);
    List<String> normalizedRoles = normalizeRoles(roles);
    int quota = normalizeQuota(dailyQuota);
    UUID id = UUID.randomUUID();
    try {
      jdbc.update("insert into app_users(id,username,password_hash,status,daily_model_quota,created_at) values(?,?,?,?,?,?)",
          id, normalizedUsername, encoder.encode(password), "ACTIVE", quota, Timestamp.from(Instant.now()));
      normalizedRoles.forEach(role -> jdbc.update("insert into app_user_roles(user_id,role) values(?,?)", id, role));
      ensurePersonalWorkspace(id, normalizedUsername);
    } catch (Exception e) { throw new IllegalArgumentException("用户名已存在或角色无效"); }
  }

  public void update(UUID id, String status, List<String> roles, Integer dailyQuota, String actor) {
    if (!Set.of("ACTIVE", "DISABLED").contains(status)) throw new IllegalArgumentException("账号状态只能为 ACTIVE 或 DISABLED");
    List<String> normalizedRoles = normalizeRoles(roles);
    if (id.equals(currentId(actor)) && (!"ACTIVE".equals(status) || !normalizedRoles.contains("ADMIN"))) throw new IllegalArgumentException("不能停用或移除当前管理员自身权限");
    Integer admins = jdbc.queryForObject("select count(distinct user_id) from app_user_roles where role='ADMIN'", Integer.class);
    boolean wasAdmin = roles(id).contains("ADMIN");
    if (wasAdmin && !normalizedRoles.contains("ADMIN") && admins != null && admins <= 1) throw new IllegalArgumentException("系统至少需要保留一个管理员账号");
    jdbc.update("update app_users set status=?,daily_model_quota=? where id=?", status, normalizeQuota(dailyQuota), id);
    jdbc.update("delete from app_user_roles where user_id=?", id);
    normalizedRoles.forEach(role -> jdbc.update("insert into app_user_roles(user_id,role) values(?,?)", id, role));
  }

  @Transactional
  public Map<String, Object> updateUsername(String currentUsername, String username) {
    UUID id = currentId(currentUsername);
    String normalized = normalizeUsername(username);
    if ("admin".equalsIgnoreCase(normalized) && !"admin".equalsIgnoreCase(currentUsername)) throw new IllegalArgumentException("admin 为系统保留用户名");
    String existing = jdbc.query("select username from app_users where lower(username)=lower(?) and id<>?", (rs, n) -> rs.getString(1), normalized, id).stream().findFirst().orElse(null);
    if (existing != null) throw new IllegalArgumentException("该用户名已被使用");
    if (!normalized.equals(currentUsername)) jdbc.update("update app_users set username=?,session_revision=session_revision+1 where id=?", normalized, id);
    return profile(normalized);
  }

  public boolean isCurrentSession(String username, String accountId, Object revision) {
    if (accountId == null || accountId.isBlank() || revision == null) return false;
    try {
      return jdbc.query("select id,session_revision,status from app_users where username=?", (rs, n) ->
          accountId.equals(rs.getString("id")) && Integer.toString(rs.getInt("session_revision")).equals(String.valueOf(revision)) && "ACTIVE".equals(rs.getString("status")), username)
          .stream().findFirst().orElse(false);
    } catch (Exception ignored) { return false; }
  }

  public Map<String, Object> personalSummary(String username) {
    UUID id = currentId(username);
    long knowledgeBases = count("select count(*) from knowledge_bases where owner_id=?", id);
    long documents = count("select count(*) from source_documents d join knowledge_bases k on d.knowledge_base_id=k.id where k.owner_id=?", id);
    long storageUsedBytes = count("select coalesce(sum(d.size_bytes),0) from source_documents d join knowledge_bases k on d.knowledge_base_id=k.id where k.owner_id=?", id);
    long storageQuotaBytes = count("select storage_quota_bytes from app_users where id=?", id);
    long standards = count("select count(*) from occupational_standards s join source_documents d on s.document_id=d.id join knowledge_bases k on d.knowledge_base_id=k.id where k.owner_id=?", id);
    long blueprints = count("select count(*) from generation_jobs where owner_id=? and job_type='BLUEPRINT'", id);
    long questionJobs = count("select count(*) from generation_jobs where owner_id=? and job_type='QUESTION_BANK'", id);
    long runningQuestionJobs = count("select count(*) from generation_jobs where owner_id=? and job_type='QUESTION_BANK' and status in ('QUEUED','RUNNING')", id);
    long deliverableQuestionBanks = count("select count(*) from generation_jobs where owner_id=? and job_type='QUESTION_BANK' and (status='SUCCEEDED' or error_code='QUESTION_COUNT_MISMATCH')", id);
    long assistantConversations = count("select count(*) from assistant_conversations where owner_id=?", id);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("knowledgeBases", knowledgeBases); result.put("documents", documents); result.put("storageUsedBytes", storageUsedBytes); result.put("storageQuotaBytes", storageQuotaBytes);
    result.put("standards", standards); result.put("blueprints", blueprints); result.put("questionJobs", questionJobs); result.put("runningQuestionJobs", runningQuestionJobs);
    result.put("deliverableQuestionBanks", deliverableQuestionBanks); result.put("assistantConversations", assistantConversations); return result;
  }
  private long count(String sql, UUID id) { Number value = jdbc.queryForObject(sql, Number.class, id); return value == null ? 0L : value.longValue(); }

  private String normalizeUsername(String username) {
    String value = username == null ? "" : username.trim();
    if (!value.matches("[\\p{IsHan}A-Za-z0-9_.-]{2,32}")) throw new IllegalArgumentException("用户名须为 2-32 位中文、字母、数字或 ._- ");
    if ("admin".equalsIgnoreCase(value) && currentUsernameExists(value)) throw new IllegalArgumentException("admin 为系统保留用户名");
    return value;
  }
  private boolean currentUsernameExists(String username) { return jdbc.queryForObject("select count(*) from app_users where lower(username)=lower(?)", Integer.class, username) > 0; }
  private void validatePassword(String password) {
    if (password == null || password.length() < 8) throw new IllegalArgumentException("初始密码至少 8 位");
  }
  private List<String> normalizeRoles(List<String> roles) {
    List<String> result = (roles == null || roles.isEmpty() ? List.of("EDITOR") : roles).stream().filter(role -> role != null && !role.isBlank()).map(role -> role.trim().toUpperCase(Locale.ROOT)).distinct().toList();
    if (result.isEmpty() || result.stream().anyMatch(role -> !ALLOWED_ROLES.contains(role))) throw new IllegalArgumentException("角色只能为 ADMIN、EDITOR 或 VIEWER");
    return result;
  }
  private int normalizeQuota(Integer quota) {
    if (quota == null) return 1000;
    if (quota < 0 || quota > 1_000_000) throw new IllegalArgumentException("每日模型额度须介于 0 和 1000000 之间");
    return quota;
  }
  private void ensurePersonalWorkspace(UUID userId, String username) {
    Integer existing = jdbc.queryForObject("select count(*) from knowledge_bases where owner_id=?", Integer.class, userId);
    if (existing != null && existing > 0) return;
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update("insert into knowledge_bases(id,name,description,owner_id,status,storage_quota_bytes,created_at,updated_at) values(?,?,?,?,?,?,?,?)",
        UUID.randomUUID(), "我的个人知识库", "系统为 " + username + " 创建的独立个人资料空间", userId, "ACTIVE", 10L * 1024 * 1024 * 1024, now, now);
  }
  private List<String> roles(UUID id) { return jdbc.query("select role from app_user_roles where user_id=? order by role", (rs, n) -> rs.getString(1), id); }
  private record Row(UUID id, String username, String hash, String status) {}
}
