package com.tikuzhushou.workflow;

import com.tikuzhushou.core.CoreBusinessService;
import com.tikuzhushou.document.ObjectStorageService;
import com.tikuzhushou.identity.AppUserService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BusinessWorkflowWorker {
  private static final Logger log = LoggerFactory.getLogger(BusinessWorkflowWorker.class);
  private static final String STANDARD = "STANDARD_PARSE";
  private static final String LEVEL_DISCOVERY = "LEVEL_DISCOVERY";
  private static final String LEVEL_STANDARD = "LEVEL_STANDARD_PARSE";
  private static final String EXPORT = "QUESTION_EXPORT";
  private final WorkflowTaskService tasks;
  private final CoreBusinessService core;
  private final ObjectStorageService storage;
  private final StringRedisTemplate redis;
  private final Executor executor;
  private final JdbcTemplate jdbc;
  private final AppUserService users;
  private final KnowledgeBaseAccessService access;
  private final boolean redisQueue;
  private final String queueKey;

  public BusinessWorkflowWorker(WorkflowTaskService tasks, CoreBusinessService core, ObjectStorageService storage,
      StringRedisTemplate redis, @Qualifier("generationExecutor") Executor executor, JdbcTemplate jdbc,
      AppUserService users, KnowledgeBaseAccessService access,
      @Value("${app.queue.provider:local}") String provider,
      @Value("${app.queue.business-key:tiku:business:jobs}") String queueKey) {
    this.tasks = tasks; this.core = core; this.storage = storage; this.redis = redis; this.executor = executor;
    this.jdbc = jdbc; this.users = users; this.access = access;
    this.redisQueue = "redis".equalsIgnoreCase(provider); this.queueKey = queueKey;
  }

  public WorkflowTaskService.Task submitStandard(UUID documentId, String idempotencyKey) {
    access.assertDocument(documentId);
    var created = tasks.create(STANDARD, documentId, idempotencyKey, "职业标准解析任务已进入队列");
    if (created.created()) enqueue(created.task().id());
    return created.task();
  }

  public WorkflowTaskService.Task submitLevelDiscovery(UUID documentId, String idempotencyKey) {
    access.assertDocument(documentId);
    var created = tasks.create(LEVEL_DISCOVERY, documentId, idempotencyKey, "职业等级识别任务已进入队列");
    if (created.created()) enqueue(created.task().id());
    return created.task();
  }

  public WorkflowTaskService.Task submitLevelStandard(UUID discoveryId, String level, String idempotencyKey) {
    var standard = core.prepareLevelStandard(discoveryId, level);
    var created = tasks.create(LEVEL_STANDARD, standard.id(), idempotencyKey, "职业等级具体标准提取任务已进入队列");
    if (created.created()) enqueue(created.task().id());
    return created.task();
  }

  public WorkflowTaskService.Task submitExport(UUID jobId, String idempotencyKey) {
    var job = core.getJob(jobId);
    if (!("SUCCEEDED".equals(job.status()) || core.partialExport(jobId))) {
      throw new IllegalArgumentException("当前题库没有可导出的合格题目");
    }
    var created = tasks.create(EXPORT, jobId, idempotencyKey, "题库导出任务已进入队列");
    if (created.created()) enqueue(created.task().id());
    return created.task();
  }

  private void enqueue(UUID taskId) {
    if (redisQueue) redis.opsForList().rightPush(queueKey, taskId.toString()); else execute(taskId);
  }

  @Scheduled(fixedDelayString = "${APP_QUEUE_POLL_MS:250}")
  public void drain() {
    if (!redisQueue) return;
    for (int index = 0; index < 4; index++) {
      String raw = redis.opsForList().leftPop(queueKey);
      if (raw == null) return;
      try { execute(UUID.fromString(raw)); } catch (IllegalArgumentException ignored) { }
    }
  }

  private void execute(UUID taskId) {
    executor.execute(() -> {
      WorkflowTaskService.Task task = tasks.systemGet(taskId);
      String initial = (STANDARD.equals(task.taskType()) || LEVEL_DISCOVERY.equals(task.taskType())
          || LEVEL_STANDARD.equals(task.taskType())) ? "DOCUMENT_QUALITY_CHECKING" : "QUESTION_SNAPSHOT_CHECKING";
      if (!tasks.claim(taskId, initial, "后台 Worker 已领取任务")) return;
      try {
        restoreOwner(task.ownerId());
        if (STANDARD.equals(task.taskType())) executeStandard(task);
        else if (LEVEL_DISCOVERY.equals(task.taskType())) executeLevelDiscovery(task);
        else if (LEVEL_STANDARD.equals(task.taskType())) executeLevelStandard(task);
        else if (EXPORT.equals(task.taskType())) executeExport(task);
        else throw new IllegalArgumentException("不支持的业务任务类型：" + task.taskType());
      } catch (TaskCancelledException cancelled) {
        tasks.update(task.id(), "CANCELLED", "CANCELLED", 100, 0, 0, "任务已取消", null, null);
        log.info("task {} ({}) cancelled", task.id(), task.taskType());
      } catch (Exception error) {
        if (tasks.cancelled(task.id())) return;
        String code = classify(task.taskType(), error);
        tasks.update(task.id(), "FAILED", code, 100, 0, 0, "后台任务处理失败", code, rootMessage(error));
        log.error("business task {} ({}) failed [{}]: {}", task.id(), task.taskType(), code, rootMessage(error), error);
      } finally { SecurityContextHolder.clearContext(); }
    });
  }

  private void executeStandard(WorkflowTaskService.Task task) {
    tasks.update(task.id(), "RUNNING", "DOCUMENT_QUALITY_CHECKING", 10, 0, 1, "正在检查解析正文和 OCR 质量", null, null);
    if (tasks.cancelled(task.id())) return;
    tasks.update(task.id(), "RUNNING", "STANDARD_AI_EXTRACTING", 30, 0, 1, "DeepSeek 正在抽取职业、等级和考核点", null, null);
    CoreBusinessService.Standard standard = core.extractStandard(task.resourceId());
    if (tasks.cancelled(task.id())) return;
    tasks.update(task.id(), "RUNNING", "SCHEMA_VALIDATING", 76, 1, 1, "正在校验结构化 Schema", null, null);
    tasks.update(task.id(), "RUNNING", "SOURCE_MAPPING", 88, standard.assessmentPoints().size(),
        standard.assessmentPoints().size(), "正在确认字段与原文证据关系", null, null);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("standardId", standard.id()); result.put("documentId", standard.documentId());
    result.put("profession", standard.profession()); result.put("occupationCode", standard.occupationCode());
    result.put("levels", standard.levels()); result.put("assessmentPoints", standard.assessmentPoints());
    result.put("levelDetails", standard.levelDetails());
    result.put("version", standard.version()); result.put("standardStatus", standard.status());
    var extraction = core.standardExtractionInfo(standard.id());
    result.put("extractionMethod", extraction.method()); result.put("modelDiagnostics", extraction.diagnostics());
    tasks.result(task.id(), result);
    String message = "RULE_FALLBACK".equals(extraction.method())
        ? "模型连续失败，已生成规则抽取草稿；请人工编辑并确认后使用"
        : "职业标准解析完成，等待人工核对确认";
    tasks.update(task.id(), "SUCCEEDED", "SUCCEEDED", 100, standard.assessmentPoints().size(),
        standard.assessmentPoints().size(), message, null, null);
    log.info("standard extraction task={} documentId={} standardId={} profession={} points={} method={}",
        task.id(), task.resourceId(), standard.id(), standard.profession(),
        standard.assessmentPoints().size(), extraction.method());
  }

  private void executeLevelDiscovery(WorkflowTaskService.Task task) {
    tasks.update(task.id(), "RUNNING", "LEVEL_DISCOVERING", 30, 0, 1, "正在识别职业名称、编码和职业等级", null, null);
    CoreBusinessService.Standard discovery = core.discoverLevels(task.resourceId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("levelDiscoveryId", discovery.id()); result.put("documentId", discovery.documentId());
    result.put("profession", discovery.profession()); result.put("occupationCode", discovery.occupationCode());
    result.put("levels", discovery.levels()); result.put("levelDetected", "LEVEL_DISCOVERY".equals(discovery.standardKind()));
    result.put("standardStatus", discovery.status());
    tasks.result(task.id(), result);
    String message = "LEVEL_DISCOVERY".equals(discovery.standardKind())
        ? "已识别职业等级，请选择一个等级继续提取具体标准"
        : "未识别出明确职业等级，可直接提取整份文档的通用职业标准";
    tasks.update(task.id(), "SUCCEEDED", "SUCCEEDED", 100, discovery.levels().size(), discovery.levels().size(), message, null, null);
  }

  private void executeLevelStandard(WorkflowTaskService.Task task) {
    tasks.update(task.id(), "RUNNING", "LEVEL_STANDARD_EXTRACTING", 30, 0, 1, "正在按所选职业等级提取具体标准", null, null);
    CoreBusinessService.Standard standard = core.extractPreparedLevelStandard(task.resourceId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("standardId", standard.id()); result.put("parentStandardId", standard.parentStandardId());
    result.put("documentId", standard.documentId()); result.put("profession", standard.profession());
    result.put("occupationCode", standard.occupationCode()); result.put("levels", standard.levels());
    result.put("assessmentPoints", standard.assessmentPoints()); result.put("levelDetails", standard.levelDetails());
    result.put("standardKind", standard.standardKind()); result.put("version", standard.version());
    result.put("standardStatus", standard.status());
    tasks.result(task.id(), result);
    tasks.update(task.id(), "SUCCEEDED", "SUCCEEDED", 100, standard.assessmentPoints().size(),
        standard.assessmentPoints().size(), "该职业等级具体标准提取完成，等待人工确认", null, null);
  }

  private void executeExport(WorkflowTaskService.Task task) throws Exception {
    var job = core.getJob(task.resourceId());
    boolean partial = core.partialExport(job.id());
    int expected = core.expectedTotal(job.id()), actual = job.result().size();
    tasks.update(task.id(), "RUNNING", "COUNT_VALIDATING", 10, actual, expected,
        partial ? "题目数量不足，将生成带警示的不完整交付文件" : "题目数量与计划一致", null, null);
    byte[] bytes = core.workbook(job.id(), (processed, total) -> {
      if (tasks.cancelled(task.id())) throw new TaskCancelledException();
      if (processed == total || processed % 25 == 0) {
        int percent = 15 + (int) Math.round(68d * processed / Math.max(1, total));
        tasks.update(task.id(), "RUNNING", "EXCEL_WRITING", percent, processed, total,
            "正在写入 Excel：" + processed + " / " + total, null, null);
      }
    });
    if (tasks.cancelled(task.id())) throw new TaskCancelledException();
    tasks.update(task.id(), "RUNNING", "EXPORT_VALIDATING", 88, actual, expected, "正在校验工作表、表头和交付说明", null, null);
    UUID artifactId = UUID.randomUUID();
    String filename = partial ? "不完整题库_" + actual + "-" + expected + ".xlsx" : "完整题库_" + actual + ".xlsx";
    tasks.update(task.id(), "RUNNING", "FILE_STORING", 95, actual, expected, "正在写入 MinIO 交付存储", null, null);
    String path = storage.put("exports/" + artifactId + ".xlsx", new ByteArrayInputStream(bytes), bytes.length,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    jdbc.update("insert into export_artifacts(id,owner_id,source_job_id,task_id,storage_path,filename,media_type,size_bytes,expected_total,actual_total,partial,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
        artifactId, task.ownerId(), job.id(), task.id(), path, filename,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.length, expected, actual, partial,
        java.sql.Timestamp.from(Instant.now()));
    tasks.result(task.id(), Map.of("artifactId", artifactId, "downloadUrl", "/api/export-artifacts/" + artifactId,
        "filename", filename, "expectedTotal", expected, "actualTotal", actual, "partial", partial));
    tasks.update(task.id(), "SUCCEEDED", partial ? "PARTIAL_EXPORT_READY" : "DOWNLOAD_READY", 100, actual, expected,
        partial ? "不完整题库已生成，下载前请确认数量差异" : "完整题库文件已生成", null, null);
    log.info("export task={} jobId={} artifactId={} actual={} expected={} partial={} bytes={}",
        task.id(), task.resourceId(), artifactId, actual, expected, partial, bytes.length);
  }

  public Artifact artifact(UUID id) {
    var rows = jdbc.query("select id,owner_id,storage_path,filename,media_type,size_bytes,partial from export_artifacts where id=?",
        (rs, n) -> new Artifact(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("owner_id")),
            rs.getString("storage_path"), rs.getString("filename"), rs.getString("media_type"),
            rs.getLong("size_bytes"), rs.getBoolean("partial")), id);
    if (rows.isEmpty()) throw new IllegalArgumentException("导出文件不存在");
    Artifact value = rows.getFirst();
    if (!access.admin() && !value.ownerId().equals(access.currentUserId())) throw new AccessDeniedException("无权下载其他用户的导出文件");
    return value;
  }

  public byte[] read(Artifact artifact) throws Exception {
    var file = storage.materialize(artifact.storagePath(), ".xlsx");
    try { return Files.readAllBytes(file); }
    finally { if (artifact.storagePath().startsWith("minio://")) Files.deleteIfExists(file); }
  }

  private void restoreOwner(UUID ownerId) {
    String username = jdbc.queryForObject("select username from app_users where id=?", String.class, ownerId);
    var principal = users.loadUserByUsername(username);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        principal, null, principal.getAuthorities()));
  }

  private String classify(String type, Exception error) {
    String message = rootMessage(error);
    if (message.contains("额度")) return "MODEL_QUOTA_EXCEEDED";
    if (STANDARD.equals(type)) return message.contains("质量") || message.contains("正文") ? "STANDARD_PARSE_FAILED" : "STANDARD_SCHEMA_INVALID";
    if (error instanceof TaskCancelledException) return "CANCELLED";
    return "EXPORT_FAILED";
  }

  private String rootMessage(Throwable error) {
    Throwable value = error; while (value.getCause() != null) value = value.getCause();
    return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    tasks.recoverable(STANDARD).forEach(this::enqueue); tasks.recoverable(LEVEL_DISCOVERY).forEach(this::enqueue);
    tasks.recoverable(LEVEL_STANDARD).forEach(this::enqueue); tasks.recoverable(EXPORT).forEach(this::enqueue);
  }

  public record Artifact(UUID id, UUID ownerId, String storagePath, String filename, String mediaType,
      long sizeBytes, boolean partial) { }
  private static final class TaskCancelledException extends RuntimeException { }
}
