package com.tikuzhushou.ops;

import com.tikuzhushou.ai.ModelConfigurationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SystemHealthService {
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;
  private final String storageProvider;
  private final String storagePath;
  private final String minioEndpoint;
  private final ModelConfigurationService modelConfiguration;
  private final int questionConcurrency;

  public SystemHealthService(JdbcTemplate jdbc, StringRedisTemplate redis,
      @Value("${app.storage.provider:local}") String storageProvider,
      @Value("${app.storage.local-path:./storage}") String storagePath,
      @Value("${app.storage.minio-endpoint:http://127.0.0.1:9000}") String minioEndpoint,
      ModelConfigurationService modelConfiguration,
      @Value("${app.ai.question-concurrency:5}") int questionConcurrency) {
    this.jdbc = jdbc; this.redis = redis; this.storageProvider = storageProvider; this.storagePath = storagePath;
    this.minioEndpoint = minioEndpoint; this.modelConfiguration = modelConfiguration;
    this.questionConcurrency = questionConcurrency;
  }

  public Map<String, Object> inspect() {
    Map<String, Object> components = new LinkedHashMap<>();
    components.put("database", check(() -> {
      Integer value = jdbc.queryForObject("select 1", Integer.class);
      return value != null && value == 1 ? "连接正常" : "查询返回异常";
    }));
    components.put("pgvector", check(() -> {
      String product;
      try (var connection = jdbc.getDataSource().getConnection()) {
        product = connection.getMetaData().getDatabaseProductName();
      }
      if (!product.toLowerCase().contains("postgresql")) return "开发数据库模式";
      String value = jdbc.queryForObject("select coalesce(to_regtype('vector')::text,'')", String.class);
      if (value == null || value.isBlank()) throw new IllegalStateException("vector 扩展未安装");
      return "vector 扩展可用";
    }));
    components.put("redis", check(() -> {
      String pong = redis.getConnectionFactory().getConnection().ping();
      if (!"PONG".equalsIgnoreCase(pong)) throw new IllegalStateException("Redis 未返回 PONG");
      return "队列连接正常";
    }));
    components.put("storage", check(this::storageHealth));
    var settings = modelConfiguration.settings();
    boolean modelConfigured = !settings.apiKey().isBlank();
    components.put("deepseek", Map.of("status", modelConfigured ? "UP" : "WARN", "latencyMs", 0,
        "detail", modelConfigured ? "密钥已配置；实际调用由任务监控" : "模型密钥未配置",
        "textModel", settings.textModel(), "questionModel", settings.questionModel(),
        "visionModel", settings.visionModel()));
    boolean up = components.values().stream().map(value -> (Map<?, ?>) value)
        .noneMatch(value -> "DOWN".equals(value.get("status")));
    return Map.of("status", up ? "UP" : "DEGRADED", "checkedAt", Instant.now(), "components", components,
        "runtime", Map.of("questionWorkers", Math.min(12, Math.max(1, questionConcurrency)),
            "fastConcurrency", 5, "qualityConcurrency", Math.min(4, questionConcurrency)));
  }

  public Map<String, Object> modelConfiguration() {
    var settings = modelConfiguration.settings();
    return Map.of("textModel", settings.textModel(), "questionModel", settings.questionModel(),
        "visionModel", settings.visionModel(), "configured", !settings.apiKey().isBlank(),
        "modes", Map.of(
            "FAST", Map.of("reasoning", "low", "concurrency", 5, "gate", "基础门禁"),
            "BALANCED", Map.of("reasoning", "high", "concurrency", Math.min(4, questionConcurrency), "gate", "完整质量门禁"),
            "TIERED", Map.of("reasoning", "low/high/max", "concurrency", Math.min(4, questionConcurrency), "gate", "按层级完整质量门禁"),
            "PROFESSIONAL_PRO", Map.of("reasoning", "high/high", "concurrency", Math.min(2, questionConcurrency), "gate", "Pro 两阶段命题与审题门禁")));
  }

  private String storageHealth() throws Exception {
    if (!"minio".equalsIgnoreCase(storageProvider)) {
      Path path = Path.of(storagePath).toAbsolutePath(); Files.createDirectories(path);
      if (!Files.isWritable(path)) throw new IllegalStateException("本地存储目录不可写");
      return "本地存储可写：" + path;
    }
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    var request = HttpRequest.newBuilder(URI.create(minioEndpoint + "/minio/health/live"))
        .timeout(Duration.ofSeconds(4)).GET().build();
    int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    if (status != 200) throw new IllegalStateException("MinIO 健康检查 HTTP " + status);
    return "MinIO 健康检查通过";
  }
  private Map<String, Object> check(Check action) {
    long start = System.nanoTime();
    try { return Map.of("status", "UP", "latencyMs", (System.nanoTime() - start) / 1_000_000,
        "detail", action.run()); }
    catch (Exception error) { return Map.of("status", "DOWN", "latencyMs", (System.nanoTime() - start) / 1_000_000,
        "detail", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
  }
  @FunctionalInterface private interface Check { String run() throws Exception; }
}
