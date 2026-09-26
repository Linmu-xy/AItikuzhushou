package com.tikuzhushou.quota;

import com.tikuzhushou.identity.AppUserService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Tracks request quota and provider-reported token usage separately from visible text length. */
@Service
public class ModelQuotaService {
  private final JdbcTemplate jdbc;
  private final AppUserService users;

  public ModelQuotaService(JdbcTemplate jdbc, AppUserService users) {
    this.jdbc = jdbc;
    this.users = users;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public synchronized Reservation reserve(int inputChars) {
    String username = SecurityContextHolder.getContext().getAuthentication().getName();
    UUID user = users.currentId(username);
    LocalDate day = LocalDate.now();
    Integer quota = jdbc.queryForObject("select daily_model_quota from app_users where id=?", Integer.class, user);
    Integer used = jdbc.query("select request_count from model_usage_daily where user_id=? and usage_date=?",
        (rs, n) -> rs.getInt(1), user, day).stream().findFirst().orElse(0);
    if (used >= quota) {
      throw new IllegalStateException("今日模型额度已用尽（" + quota + " 次），请明日再试或由管理员调整额度");
    }
    if (used == 0) {
      jdbc.update("insert into model_usage_daily(user_id,usage_date,request_count,input_chars,output_chars,input_tokens,output_tokens,reasoning_tokens) values(?,?,?,?,0,0,0,0)",
          user, day, 1, inputChars);
    } else {
      jdbc.update("update model_usage_daily set request_count=request_count+1,input_chars=input_chars+? where user_id=? and usage_date=?",
          inputChars, user, day);
    }
    return new Reservation(user, day, inputChars);
  }

  /** Backward-compatible completion accounting for non-DeepSeek callers. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void complete(Reservation reservation, int outputChars) {
    complete(reservation, outputChars, Usage.EMPTY);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void complete(Reservation reservation, int outputChars, Usage usage) {
    Usage safe = usage == null ? Usage.EMPTY : usage;
    jdbc.update("update model_usage_daily set output_chars=output_chars+?,input_tokens=input_tokens+?,output_tokens=output_tokens+?,reasoning_tokens=reasoning_tokens+? where user_id=? and usage_date=?",
        outputChars, safe.promptTokens(), safe.completionTokens(), safe.reasoningTokens(), reservation.userId(), reservation.day());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordEvent(Reservation reservation, String model, String effort, boolean thinking,
      int outputChars, Usage usage, long durationMs) {
    recordEvent(reservation, model, effort, thinking, "UNKNOWN", outputChars, usage, durationMs);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordEvent(Reservation reservation, String model, String effort, boolean thinking,
      String operation, int outputChars, Usage usage, long durationMs) {
    Usage safe = usage == null ? Usage.EMPTY : usage;
    try {
      jdbc.update("insert into model_usage_events(id,user_id,usage_date,model,reasoning_effort,thinking_enabled,operation,input_chars,output_chars,prompt_tokens,completion_tokens,reasoning_tokens,duration_ms,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
          UUID.randomUUID(), reservation.userId(), reservation.day(), model, effort, thinking, operation,
          reservation.inputChars(), outputChars, safe.promptTokens(), safe.completionTokens(), safe.reasoningTokens(),
          Math.max(0, durationMs));
    } catch (Exception ignored) {
      // Usage accounting must never turn a successful provider response into a failed question.
    }
  }

  public Map<String, Object> mine() {
    String username = SecurityContextHolder.getContext().getAuthentication().getName();
    UUID user = users.currentId(username);
    LocalDate day = LocalDate.now();
    Integer quota = jdbc.queryForObject("select daily_model_quota from app_users where id=?", Integer.class, user);
    var usage = jdbc.query("select request_count,input_chars,output_chars,input_tokens,output_tokens,reasoning_tokens from model_usage_daily where user_id=? and usage_date=?",
        (rs, n) -> {
          Map<String, Object> value = new LinkedHashMap<>();
          value.put("requests", rs.getInt(1));
          value.put("inputChars", rs.getLong(2));
          value.put("outputChars", rs.getLong(3));
          value.put("promptTokens", rs.getLong(4));
          value.put("completionTokens", rs.getLong(5));
          value.put("reasoningTokens", rs.getLong(6));
          return value;
        }, user, day).stream().findFirst().orElseGet(() -> new LinkedHashMap<>(Map.of(
            "requests", 0, "inputChars", 0L, "outputChars", 0L,
            "promptTokens", 0L, "completionTokens", 0L, "reasoningTokens", 0L)));
    Map<String, Object> result = new LinkedHashMap<>(usage);
    result.put("accountId", user.toString());
    result.put("accountName", username);
    result.put("dailyQuota", quota);
    result.put("remaining", quota - ((Number) usage.get("requests")).intValue());
    result.put("date", day.toString());
    result.put("nextResetAt", day.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toString());
    return result;
  }

  /** Per-model/reasoning breakdown used to find the expensive stage after a generation run. */
  public List<Map<String, Object>> breakdown() {
    String username = SecurityContextHolder.getContext().getAuthentication().getName();
    UUID user = users.currentId(username);
    return jdbc.query("select model,reasoning_effort,thinking_enabled,operation,count(*) request_count "
        + ",coalesce(sum(input_chars),0) input_chars,coalesce(sum(output_chars),0) output_chars "
        + ",coalesce(sum(prompt_tokens),0) prompt_tokens,coalesce(sum(completion_tokens),0) completion_tokens "
        + ",coalesce(sum(reasoning_tokens),0) reasoning_tokens,coalesce(avg(duration_ms),0) avg_duration_ms "
        + "from model_usage_events where user_id=? and usage_date=current_date "
        + "group by model,reasoning_effort,thinking_enabled,operation order by reasoning_tokens desc, input_chars desc",
        (rs, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("model", rs.getString("model"));
          row.put("reasoningEffort", rs.getString("reasoning_effort"));
          row.put("thinkingEnabled", rs.getBoolean("thinking_enabled"));
          row.put("operation", rs.getString("operation"));
          row.put("requests", rs.getInt("request_count"));
          row.put("inputChars", rs.getLong("input_chars"));
          row.put("outputChars", rs.getLong("output_chars"));
          row.put("promptTokens", rs.getLong("prompt_tokens"));
          row.put("completionTokens", rs.getLong("completion_tokens"));
          row.put("reasoningTokens", rs.getLong("reasoning_tokens"));
          row.put("avgDurationMs", Math.round(rs.getDouble("avg_duration_ms")));
          return row;
        }, user);
  }

  public record Reservation(UUID userId, LocalDate day, int inputChars) { }

  public record Usage(long promptTokens, long completionTokens, long reasoningTokens) {
    public static final Usage EMPTY = new Usage(0, 0, 0);
  }
}
