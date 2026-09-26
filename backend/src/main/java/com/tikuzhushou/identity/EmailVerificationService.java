package com.tikuzhushou.identity;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Mail delivery boundary. Until SMTP is configured, local mode deliberately exposes the code only to the caller. */
@Service
public class EmailVerificationService {
  public static final String REGISTER = "REGISTER";
  public static final String LOGIN = "LOGIN";
  private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
  private final JdbcTemplate jdbc;
  private final PasswordEncoder encoder;
  private final boolean localCodeMode;
  private final SecureRandom random = new SecureRandom();

  public EmailVerificationService(JdbcTemplate jdbc, PasswordEncoder encoder,
      @Value("${app.mail.local-code-mode:true}") boolean localCodeMode) {
    this.jdbc = jdbc; this.encoder = encoder; this.localCodeMode = localCodeMode;
  }

  public Map<String, Object> issue(String email, String purpose) {
    String target = normalizedEmail(email); validatePurpose(purpose);
    if (!localCodeMode) {
      throw new IllegalStateException("EMAIL_DELIVERY_NOT_CONFIGURED：生产环境尚未配置 SMTP 邮件服务");
    }
    Instant now = Instant.now();
    var latest = jdbc.query("select created_at from email_verification_codes where lower(email)=lower(?) and purpose=? and consumed_at is null order by created_at desc limit 1",
        (rs, n) -> rs.getTimestamp(1).toInstant(), target, purpose);
    if (!latest.isEmpty() && latest.getFirst().plusSeconds(60).isAfter(now)) throw new IllegalStateException("EMAIL_CODE_RATE_LIMITED：验证码已发送，请 60 秒后重试");
    String code = "%06d".formatted(random.nextInt(1_000_000));
    jdbc.update("insert into email_verification_codes(id,email,purpose,code_hash,attempts,expires_at,created_at) values(?,?,?,?,0,?,?)",
        UUID.randomUUID(), target, purpose, encoder.encode(code), Timestamp.from(now.plusSeconds(600)), Timestamp.from(now));
    return Map.of("status", "CODE_ISSUED", "delivery", "LOCAL_DEVELOPMENT", "expiresInSeconds", 600, "debugCode", code);
  }

  public void verify(String email, String purpose, String code) {
    String target = normalizedEmail(email); validatePurpose(purpose);
    var rows = jdbc.query("select id,code_hash,attempts,expires_at from email_verification_codes where lower(email)=lower(?) and purpose=? and consumed_at is null order by created_at desc limit 1",
        (rs, n) -> new CodeRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getTimestamp(4).toInstant()), target, purpose);
    CodeRow row = rows.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("EMAIL_CODE_NOT_FOUND：请先获取验证码"));
    if (row.expiresAt().isBefore(Instant.now())) throw new IllegalArgumentException("EMAIL_CODE_EXPIRED：验证码已过期，请重新获取");
    if (row.attempts() >= 5) throw new IllegalArgumentException("EMAIL_CODE_LOCKED：验证码错误次数过多，请重新获取");
    if (!encoder.matches(code == null ? "" : code.trim(), row.hash())) {
      jdbc.update("update email_verification_codes set attempts=attempts+1 where id=?", row.id());
      throw new IllegalArgumentException("EMAIL_CODE_INVALID：验证码错误");
    }
    jdbc.update("update email_verification_codes set consumed_at=? where id=?", Timestamp.from(Instant.now()), row.id());
  }

  public static String normalizedEmail(String email) {
    String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    if (!value.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")) throw new IllegalArgumentException("邮箱格式无效");
    return value;
  }
  private void validatePurpose(String purpose) { if (!REGISTER.equals(purpose) && !LOGIN.equals(purpose)) throw new IllegalArgumentException("不支持的验证码用途"); }
  @Scheduled(cron = "0 40 3 * * *")
  public void retain() {
    int removed = jdbc.update("delete from email_verification_codes where created_at < ?",
        Timestamp.from(Instant.now().minusSeconds(7L * 24 * 3600)));
    log.info("email verification code retention removed {} expired rows", removed);
  }
  private record CodeRow(UUID id, String hash, int attempts, Instant expiresAt) { }
}
