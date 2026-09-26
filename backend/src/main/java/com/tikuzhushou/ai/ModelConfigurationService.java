package com.tikuzhushou.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Keeps provider credentials server-side and encrypts admin-managed credentials at rest. */
@Service
public class ModelConfigurationService {
  private static final String DEEPSEEK = "DEEPSEEK";
  private final JdbcTemplate jdbc;
  private final String fallbackKey;
  private final String fallbackTextModel;
  private final String fallbackQuestionModel;
  private final String fallbackVisionModel;
  private final String encryptionSecret;
  private final SecureRandom random = new SecureRandom();

  public ModelConfigurationService(JdbcTemplate jdbc, @Value("${app.ai.api-key}") String fallbackKey,
      @Value("${app.ai.text-model}") String fallbackTextModel,
      @Value("${app.ai.question-model}") String fallbackQuestionModel,
      @Value("${app.ai.vision-model}") String fallbackVisionModel,
      @Value("${app.security.config-encryption-key:}") String encryptionSecret) {
    this.jdbc = jdbc;
    this.fallbackKey = Objects.toString(fallbackKey, "").trim();
    this.fallbackTextModel = Objects.toString(fallbackTextModel, "deepseek-flash").trim();
    this.fallbackQuestionModel = Objects.toString(fallbackQuestionModel, this.fallbackTextModel).trim();
    this.fallbackVisionModel = Objects.toString(fallbackVisionModel, "deepseek-v4-flash-vision-exp").trim();
    // The credential encryption key MUST come only from APP_CONFIG_ENCRYPTION_KEY and be fixed
    // for the lifetime of the managed secret. Falling back to the bootstrap admin password or the
    // provider API key made an environment change silently lock the stored credential forever.
    this.encryptionSecret = Objects.toString(encryptionSecret, "").trim();
  }

  public Settings settings() {
    return jdbc.query("select encrypted_api_key,text_model,question_model,vision_model from model_provider_configs where config_key=?",
        (rs, n) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), DEEPSEEK).stream().findFirst()
        .map(row -> new Settings(decrypt(row.encryptedApiKey()), row.textModel(),
            defaultModel(row.questionModel(), fallbackQuestionModel), row.visionModel(), true))
        .orElseGet(() -> new Settings(fallbackKey, fallbackTextModel, fallbackQuestionModel, fallbackVisionModel, false));
  }

  public Map<String, Object> publicConfiguration() {
    Settings settings = settings();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("provider", DEEPSEEK);
    result.put("configured", !settings.apiKey().isBlank());
    result.put("apiKeyMasked", mask(settings.apiKey()));
    result.put("textModel", settings.textModel());
    result.put("questionModel", settings.questionModel());
    result.put("visionModel", settings.visionModel());
    result.put("managedInDatabase", settings.managedInDatabase());
    result.put("encryptionReady", encryptionReady());
    return result;
  }

  public void update(String apiKey, String textModel, String questionModel, String visionModel, String actor) {
    if (!encryptionReady()) throw new IllegalStateException(
        "CONFIG_ENCRYPTION_KEY_MISSING：尚未配置 APP_CONFIG_ENCRYPTION_KEY，无法安全保存模型密钥。请先在服务器环境变量中固定该密钥后重启。");
    Settings current = settings();
    String nextKey = Objects.toString(apiKey, "").trim();
    if (nextKey.isBlank()) nextKey = current.apiKey();
    if (nextKey.isBlank() || nextKey.length() < 16) throw new IllegalArgumentException("API Key 无效，请输入完整模型密钥");
    String nextText = validModel(textModel, "文本模型", current.textModel());
    String nextQuestion = validModel(questionModel, "命题模型", current.questionModel());
    String nextVision = validModel(visionModel, "视觉模型", current.visionModel());
    Instant now = Instant.now();
    int changed = jdbc.update("update model_provider_configs set encrypted_api_key=?,key_fingerprint=?,text_model=?,question_model=?,vision_model=?,updated_by=?,updated_at=? where config_key=?",
        encrypt(nextKey), fingerprint(nextKey), nextText, nextQuestion, nextVision, actor, Timestamp.from(now), DEEPSEEK);
    if (changed == 0) jdbc.update("insert into model_provider_configs(config_key,encrypted_api_key,key_fingerprint,text_model,question_model,vision_model,updated_by,updated_at) values(?,?,?,?,?,?,?,?)",
        DEEPSEEK, encrypt(nextKey), fingerprint(nextKey), nextText, nextQuestion, nextVision, actor, Timestamp.from(now));
  }

  private String validModel(String value, String label, String fallback) {
    String candidate = Objects.toString(value, "").trim();
    if (candidate.isBlank()) candidate = fallback;
    if (!candidate.matches("[A-Za-z0-9._:-]{3,120}")) throw new IllegalArgumentException(label + "名称格式无效");
    return candidate;
  }
  private String defaultModel(String value, String fallback) {
    String candidate = Objects.toString(value, "").trim();
    return candidate.isBlank() ? fallback : candidate;
  }
  private boolean encryptionReady() { return encryptionSecret.length() >= 16; }
  private String encrypt(String plain) {
    if (!encryptionReady()) throw new IllegalStateException(
        "CONFIG_ENCRYPTION_KEY_MISSING：尚未配置 APP_CONFIG_ENCRYPTION_KEY，无法加密模型密钥");
    try {
      byte[] iv = new byte[12]; random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] joined = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, joined, 0, iv.length); System.arraycopy(encrypted, 0, joined, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(joined);
    } catch (Exception error) { throw new IllegalStateException("模型密钥加密失败", error); }
  }
  private String decrypt(String encrypted) {
    if (!encryptionReady()) throw new IllegalStateException(
        "CONFIG_ENCRYPTION_KEY_MISSING：尚未配置 APP_CONFIG_ENCRYPTION_KEY，无法读取已托管模型密钥。请固定该密钥后重启；若密钥已更换，请在管理端重新保存模型密钥。");
    try {
      byte[] joined = Base64.getDecoder().decode(encrypted);
      byte[] iv = java.util.Arrays.copyOfRange(joined, 0, 12);
      byte[] payload = java.util.Arrays.copyOfRange(joined, 12, joined.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
    } catch (Exception error) {
      throw new IllegalStateException(
          "CONFIG_ENCRYPTION_KEY_MISMATCH：模型密钥由不同的 APP_CONFIG_ENCRYPTION_KEY 加密，当前密钥无法解密。请恢复原加密密钥，或在管理端重新保存模型密钥。", error);
    }
  }
  private SecretKeySpec key() throws Exception {
    return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(encryptionSecret.getBytes(StandardCharsets.UTF_8)), "AES");
  }
  private String fingerprint(String key) { return "sha256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest(key)).substring(0, 12); }
  private byte[] digest(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch (Exception error) { throw new IllegalStateException(error); } }
  private String mask(String value) { return value.isBlank() ? "未配置" : value.length() < 9 ? "已配置" : value.substring(0, 3) + "••••" + value.substring(value.length() - 4); }

  public record Settings(String apiKey, String textModel, String questionModel, String visionModel,
      boolean managedInDatabase) { }
  private record Stored(String encryptedApiKey, String textModel, String questionModel, String visionModel) { }
}
