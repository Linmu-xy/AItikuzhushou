package com.tikuzhushou.cad;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.document.ObjectStorageService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Runs the isolated CAD Worker and persists a versioned, reviewable fact set. */
@Service
public class CadAnalysisService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final CadMaterialService materials;
  private final KnowledgeBaseAccessService access;
  private final ObjectStorageService storage;
  private final String workerCommand;
  private final String workerScript;
  private final String workerPythonPath;
  private final Path workRoot;
  private final long timeoutSeconds;
  private final double deflection;
  private final int maxTriangles;
  private final int drawingDpi;
  private final int drawingMaxPages;

  public CadAnalysisService(JdbcTemplate jdbc, ObjectMapper json, CadMaterialService materials,
      KnowledgeBaseAccessService access, ObjectStorageService storage,
      @Value("${app.cad.worker-command:python}") String workerCommand,
      @Value("${app.cad.worker-script:../tools/cad_worker/worker.py}") String workerScript,
      @Value("${app.cad.worker-python-path:}") String workerPythonPath,
      @Value("${app.cad.work-root:./cad-work}") String workRoot,
      @Value("${app.cad.timeout-seconds:300}") long timeoutSeconds,
      @Value("${app.cad.deflection:0.5}") double deflection,
      @Value("${app.cad.max-triangles:250000}") int maxTriangles,
      @Value("${app.cad.drawing-dpi:300}") int drawingDpi,
      @Value("${app.cad.drawing-max-pages:40}") int drawingMaxPages) {
    this.jdbc = jdbc;
    this.json = json;
    this.materials = materials;
    this.access = access;
    this.storage = storage;
    this.workerCommand = Objects.requireNonNullElse(workerCommand, "python").trim();
    this.workerScript = Objects.requireNonNullElse(workerScript, "../tools/cad_worker/worker.py").trim();
    this.workerPythonPath = Objects.requireNonNullElse(workerPythonPath, "").trim();
    this.workRoot = Path.of(Objects.requireNonNullElse(workRoot, "./cad-work"));
    this.timeoutSeconds = Math.max(10, timeoutSeconds);
    this.deflection = Math.max(0.001, deflection);
    this.maxTriangles = Math.max(1_000, maxTriangles);
    this.drawingDpi = Math.max(220, Math.min(400, drawingDpi));
    this.drawingMaxPages = Math.max(1, Math.min(120, drawingMaxPages));
  }

  public AnalysisJob createJob(UUID materialId, UUID workflowTaskId) {
    CadMaterialService.Material material = materials.get(materialId);
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.update("insert into cad_analysis_jobs(id,material_id,workflow_task_id,parser,status,created_at,updated_at) values(?,?,?,?,?,?,?)",
        id, material.id(), workflowTaskId, "cad-worker", "QUEUED", Timestamp.from(now), Timestamp.from(now));
    materials.updateStatus(material.id(), "ANALYSIS_QUEUED");
    return new AnalysisJob(id, material.id(), workflowTaskId, "cad-worker", "QUEUED", null, null, null, null, null,
        now, now);
  }

  public Map<String, Object> run(AnalysisJob job) {
    CadMaterialService.Material material = materials.get(job.materialId());
    Path runRoot = workRoot.resolve(job.id().toString()).toAbsolutePath().normalize();
    Path input = null;
    try {
      jdbc.update("update cad_analysis_jobs set status='RUNNING',updated_at=? where id=?", Timestamp.from(Instant.now()), job.id());
      materials.updateStatus(material.id(), "ANALYZING");
      Files.createDirectories(runRoot);
      input = materials.materialize(material);
      Path output = runRoot.resolve("analysis.json");
      Path preview = runRoot.resolve("preview.obj");
      Path script = resolveWorkerScript();
      List<String> command = new ArrayList<>(List.of(workerCommand, script.toString(), "--input", input.toString(),
          "--output", output.toString(), "--preview", preview.toString(), "--deflection", Double.toString(deflection),
          "--max-triangles", Integer.toString(maxTriangles)));
      ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
      if (!workerPythonPath.isBlank()) builder.environment().put("PYTHONPATH", workerPythonPath);
      Process process = builder.start();
      boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      String console = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!finished) {
        process.destroyForcibly();
        Map<String, Object> fallback = tryDrawingFallback(job, material, input, runRoot,
            "CAD_WORKER_TIMEOUT", "结构化解析超时");
        if (fallback != null) return fallback;
        return fail(job, "CAD_WORKER_TIMEOUT", "CAD Worker 超时，已终止解析进程");
      }
      if (!Files.exists(output)) {
        Map<String, Object> fallback = tryDrawingFallback(job, material, input, runRoot,
            "CAD_WORKER_NO_RESULT", "结构化解析未生成结果");
        if (fallback != null) return fallback;
        return fail(job, "CAD_WORKER_NO_RESULT", limit("CAD Worker 未生成分析结果。" + console, 2000));
      }
      Map<String, Object> result = json.readValue(Files.readString(output), new TypeReference<>() { });
      if ("PDF".equalsIgnoreCase(material.format())) {
        try {
          result.put("drawingPages", renderDrawingPages(material.id(), job.id(), input, runRoot));
        } catch (Exception renderError) {
          addWarning(result, "PDF 高清图纸渲染失败：" + rootMessage(renderError));
          result.put("drawingPages", List.of());
        }
      } else {
        result.putIfAbsent("drawingPages", List.of());
      }
      String status = Objects.toString(result.get("status"), "FAILED");
      String parser = Objects.toString(result.get("parser"), "cad-worker");
      String analysisKey = "cad_analysis_" + job.id() + ".json";
      byte[] analysisBytes = json.writeValueAsBytes(result);
      String analysisStored = storage.put(analysisKey, new java.io.ByteArrayInputStream(analysisBytes), analysisBytes.length, "application/json");
      String previewKey = null;
      String previewStoredKey = null;
      if (Files.isRegularFile(preview)) {
        byte[] previewBytes = Files.readAllBytes(preview);
        previewKey = "cad_preview_" + job.id() + ".obj";
        previewStoredKey = storage.put(previewKey, new java.io.ByteArrayInputStream(previewBytes), previewBytes.length, "text/plain");
        jdbc.update("insert into cad_preview_assets(id,material_id,analysis_job_id,asset_type,media_type,storage_key,size_bytes,created_at) values(?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), material.id(), job.id(), "OBJ", "text/plain", previewStoredKey, previewBytes.length, Timestamp.from(Instant.now()));
      }
      persistFacts(job, material.id(), result);
      jdbc.update("update cad_analysis_jobs set parser=?,status=?,result_json=?,analysis_storage_key=?,preview_storage_key=?,updated_at=? where id=?",
          parser, status, json.writeValueAsString(result), analysisStored, previewStoredKey, Timestamp.from(Instant.now()), job.id());
      materials.updateStatus(material.id(), statusToMaterial(status));
      touchExamProjects(material.id());
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("analysisJobId", job.id());
      response.put("status", status);
      response.put("parser", parser);
      response.put("previewAvailable", previewKey != null);
      response.put("facts", result.getOrDefault("facts", List.of()));
      response.put("annotations", result.getOrDefault("annotations", List.of()));
      response.put("drawingPages", result.getOrDefault("drawingPages", List.of()));
      response.put("warnings", result.getOrDefault("warnings", List.of()));
      return response;
    } catch (Exception error) {
      Map<String, Object> fallback = tryDrawingFallback(job, material, input, runRoot,
          "CAD_WORKER_FAILED", rootMessage(error));
      if (fallback != null) return fallback;
      return fail(job, "CAD_WORKER_FAILED", rootMessage(error));
    } finally {
      try { storage.cleanupMaterialized(input); } catch (Exception ignored) { }
      cleanupRunRoot(runRoot);
    }
  }

  public AnalysisJob jobByTask(UUID workflowTaskId) {
    List<AnalysisJob> rows = jdbc.query("select id,material_id,workflow_task_id,parser,status,result_json,analysis_storage_key,preview_storage_key,error_code,error_message,created_at,updated_at from cad_analysis_jobs where workflow_task_id=?",
        (rs, row) -> mapJob(rs), workflowTaskId);
    if (rows.isEmpty()) throw new IllegalArgumentException("CAD 分析任务不存在");
    return rows.getFirst();
  }

  public AnalysisView latest(UUID materialId) {
    CadMaterialService.Material material = materials.get(materialId);
    List<AnalysisJob> jobs = jdbc.query("select id,material_id,workflow_task_id,parser,status,result_json,analysis_storage_key,preview_storage_key,error_code,error_message,created_at,updated_at from cad_analysis_jobs where material_id=? order by created_at desc",
        (rs, row) -> mapJob(rs), material.id());
    if (jobs.isEmpty()) return new AnalysisView(material, null, List.of(), List.of(), List.of(), List.of());
    AnalysisJob job = jobs.getFirst();
    List<Fact> facts = jdbc.query("select id,material_id,analysis_job_id,fact_name,value_json,unit,source_ref,confidence,verified,usable_for_generation,verification_note,verified_by,verified_at,created_at from cad_facts where analysis_job_id=? order by created_at,id",
        (rs, row) -> mapFact(rs), job.id());
    List<Annotation> annotations = jdbc.query("select id,material_id,analysis_job_id,annotation_kind,page_number,value_json,source_ref,confidence,verified,usable_for_generation,verification_note,verified_by,verified_at,created_at from cad_annotations where analysis_job_id=? order by page_number,id",
        (rs, row) -> mapAnnotation(rs), job.id());
    List<PreviewAsset> previewAssets = jdbc.query("select id,material_id,analysis_job_id,asset_type,page_number,media_type,storage_key,size_bytes,created_at from cad_preview_assets where analysis_job_id=? order by page_number,id",
        (rs, row) -> mapPreviewAsset(rs), job.id());
    return new AnalysisView(material, job, facts, annotations, previewAssets, resultWarnings(job.resultJson()));
  }

  public Fact verifyFact(UUID factId, boolean verified, boolean usableForGeneration, String note) {
    List<Fact> rows = jdbc.query("select id,material_id,analysis_job_id,fact_name,value_json,unit,source_ref,confidence,verified,usable_for_generation,verification_note,verified_by,verified_at,created_at from cad_facts where id=?",
        (rs, row) -> mapFact(rs), factId);
    if (rows.isEmpty()) throw new IllegalArgumentException("模型事实不存在");
    Fact current = rows.getFirst();
    materials.get(current.materialId());
    if (usableForGeneration && !verified) throw new IllegalArgumentException("只有已确认事实才能用于出题");
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update("update cad_facts set verified=?,usable_for_generation=?,verification_note=?,verified_by=?,verified_at=? where id=?",
        verified, verified && usableForGeneration, limit(note, 1000), verified ? access.currentUserId() : null,
        verified ? now : null, factId);
    touchExamProjects(current.materialId());
    return jdbc.queryForObject("select id,material_id,analysis_job_id,fact_name,value_json,unit,source_ref,confidence,verified,usable_for_generation,verification_note,verified_by,verified_at,created_at from cad_facts where id=?",
        (rs, row) -> mapFact(rs), factId);
  }

  public Path materializeAsset(UUID materialId, String assetType) throws Exception {
    materials.get(materialId);
    List<String> keys = jdbc.query("select storage_key from cad_preview_assets where material_id=? and asset_type=? order by created_at desc",
        (rs, row) -> rs.getString(1), materialId, assetType);
    if (keys.isEmpty()) throw new IllegalArgumentException("预览文件不存在");
    return storage.materialize(keys.getFirst(), ".obj");
  }

  public Path materializeDrawingPage(UUID materialId, int page) throws Exception {
    if (page < 1) throw new IllegalArgumentException("图纸页码必须从 1 开始");
    materials.get(materialId);
    List<String> keys = jdbc.query("select storage_key from cad_preview_assets where material_id=? and asset_type='DRAWING_PAGE' and page_number=? order by created_at desc",
        (rs, row) -> rs.getString(1), materialId, page);
    if (keys.isEmpty()) throw new IllegalArgumentException("图纸页面不存在");
    return storage.materialize(keys.getFirst(), ".png");
  }

  public int drawingPageCount(UUID materialId) {
    materials.get(materialId);
    Integer count = jdbc.queryForObject("select count(*) from cad_preview_assets where material_id=? and asset_type='DRAWING_PAGE'",
        Integer.class, materialId);
    return count == null ? 0 : count;
  }

  public byte[] drawingPageBytes(UUID materialId, int page) throws Exception {
    Path path = materializeDrawingPage(materialId, page);
    try {
      return Files.readAllBytes(path);
    } finally {
      storage.cleanupMaterialized(path);
    }
  }

  private List<Map<String, Object>> renderDrawingPages(UUID materialId, UUID analysisJobId, Path input, Path runRoot) throws Exception {
    List<Map<String, Object>> pages = new ArrayList<>();
    try (var document = Loader.loadPDF(input.toFile())) {
      int pageCount = Math.min(document.getNumberOfPages(), drawingMaxPages);
      PDFRenderer renderer = new PDFRenderer(document);
      for (int index = 0; index < pageCount; index++) {
        BufferedImage image = renderer.renderImageWithDPI(index, drawingDpi, ImageType.RGB);
        Path pagePath = runRoot.resolve(String.format("drawing-page-%03d.png", index + 1));
        if (!ImageIO.write(image, "png", pagePath.toFile())) throw new IllegalStateException("JVM 未安装 PNG 编码器");
        byte[] bytes = Files.readAllBytes(pagePath);
        UUID assetId = UUID.randomUUID();
        String key = "cad_drawing_page_" + analysisJobId + "_" + (index + 1) + ".png";
        String stored = storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/png");
        jdbc.update("insert into cad_preview_assets(id,material_id,analysis_job_id,asset_type,page_number,media_type,storage_key,size_bytes,created_at) values(?,?,?,?,?,?,?,?,?)",
            assetId, materialId, analysisJobId, "DRAWING_PAGE", index + 1, "image/png", stored, bytes.length, Timestamp.from(Instant.now()));
        pages.add(Map.of("assetId", assetId, "page", index + 1, "width", image.getWidth(), "height", image.getHeight(),
            "dpi", drawingDpi, "mediaType", "image/png"));
      }
      if (document.getNumberOfPages() > drawingMaxPages) {
        pages.add(Map.of("warning", "图纸页数超过上限，仅渲染前 " + drawingMaxPages + " 页"));
      }
    }
    return pages;
  }

  /**
   * A PDF drawing is still usable when the CAD/text worker cannot build structured facts.
   * Keep its rendered pages as the authoritative visual evidence instead of turning the
   * whole material into a hard failure.
   */
  private Map<String, Object> tryDrawingFallback(AnalysisJob job, CadMaterialService.Material material,
      Path input, Path runRoot, String code, String reason) {
    if (!"PDF".equalsIgnoreCase(material.format()) || input == null || !Files.isRegularFile(input)) return null;
    // If the worker already produced analysis.json, an error after that point is a
    // persistence/storage failure and must remain a real failure rather than being
    // relabeled as a visual fallback.
    if (Files.exists(runRoot.resolve("analysis.json"))) return null;
    try {
      List<Map<String, Object>> pages = renderDrawingPages(material.id(), job.id(), input, runRoot);
      if (pages.isEmpty()) return null;
      String safeReason = limit(Objects.requireNonNullElse(reason, "结构化解析未完成"), 600);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("schemaVersion", "cad-fact.v1");
      result.put("generatedAt", Instant.now().toString());
      result.put("parser", "pdf-page-fallback");
      result.put("status", "PARSED_PARTIAL");
      result.put("facts", List.of());
      result.put("annotations", List.of());
      result.put("drawingPages", pages);
      result.put("warnings", List.of(
          "工程图结构化解析未完成（" + code + "）：" + safeReason + "；未提取尺寸、标注和视图关系。",
          "已保留原 PDF 高清页面，可继续作为随题原图使用；图中具体数值和位置必须人工核验。"));
      byte[] analysisBytes = json.writeValueAsBytes(result);
      String analysisStored = storage.put("cad_analysis_" + job.id() + ".json",
          new ByteArrayInputStream(analysisBytes), analysisBytes.length, "application/json");
      jdbc.update("update cad_analysis_jobs set parser=?,status='PARSED_PARTIAL',result_json=?,analysis_storage_key=?,preview_storage_key=null,error_code=null,error_message=null,updated_at=? where id=?",
          "pdf-page-fallback", json.writeValueAsString(result), analysisStored, Timestamp.from(Instant.now()), job.id());
      materials.updateStatus(material.id(), "ANALYSIS_PARTIAL");
      touchExamProjects(material.id());
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("analysisJobId", job.id());
      response.put("status", "PARSED_PARTIAL");
      response.put("parser", "pdf-page-fallback");
      response.put("previewAvailable", false);
      response.put("facts", List.of());
      response.put("annotations", List.of());
      response.put("drawingPages", pages);
      response.put("warnings", result.get("warnings"));
      return response;
    } catch (Exception ignored) {
      return null;
    }
  }

  private void addWarning(Map<String, Object> result, String warning) {
    List<Object> warnings = new ArrayList<>();
    Object current = result.get("warnings");
    if (current instanceof List<?> values) warnings.addAll(values);
    warnings.add(warning);
    result.put("warnings", warnings);
  }

  private void persistFacts(AnalysisJob job, UUID materialId, Map<String, Object> result) throws Exception {
    jdbc.update("delete from cad_facts where analysis_job_id=?", job.id());
    jdbc.update("delete from cad_annotations where analysis_job_id=?", job.id());
    Object facts = result.get("facts");
    if (facts instanceof List<?> list) for (Object value : list) {
      if (!(value instanceof Map<?, ?> raw)) continue;
      Map<String, Object> item = toStringMap(raw);
      jdbc.update("insert into cad_facts(id,material_id,analysis_job_id,fact_name,value_json,unit,source_ref,confidence,verified,usable_for_generation,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(), materialId, job.id(), Objects.toString(item.get("name"), "UNKNOWN"),
          json.writeValueAsString(item.get("value")), item.get("unit"), Objects.toString(item.get("source"), ""),
          number(item.get("confidence"), 0d), false, false, Timestamp.from(Instant.now()));
    }
    Object annotations = result.get("annotations");
    if (annotations instanceof List<?> list) for (Object value : list) {
      if (!(value instanceof Map<?, ?> raw)) continue;
      Map<String, Object> item = toStringMap(raw);
      Object page = item.get("page");
      jdbc.update("insert into cad_annotations(id,material_id,analysis_job_id,annotation_kind,page_number,value_json,source_ref,confidence,verified,usable_for_generation,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(), materialId, job.id(), Objects.toString(item.get("kind"), "UNKNOWN"), page instanceof Number n ? n.intValue() : null,
          json.writeValueAsString(item), Objects.toString(item.get("handle"), ""), number(item.get("confidence"), 0d), false, false,
          Timestamp.from(Instant.now()));
    }
  }

  private void touchExamProjects(UUID materialId) {
    jdbc.update("update exam_projects set updated_at=? where id in (select project_id from exam_project_sources where cad_material_id=? and enabled=true)",
        Timestamp.from(Instant.now()), materialId);
  }

  private Map<String, Object> fail(AnalysisJob job, String code, String message) {
    jdbc.update("update cad_analysis_jobs set status='FAILED',error_code=?,error_message=?,updated_at=? where id=?",
        code, limit(message, 2000), Timestamp.from(Instant.now()), job.id());
    materials.updateStatus(job.materialId(), "ANALYSIS_FAILED");
    return Map.of("analysisJobId", job.id(), "status", "FAILED", "errorCode", code, "message", limit(message, 500));
  }

  private Path resolveWorkerScript() {
    Path path = Path.of(workerScript);
    if (!path.isAbsolute()) path = Path.of(System.getProperty("user.dir")).resolve(path).normalize();
    if (!Files.isRegularFile(path)) throw new IllegalStateException("CAD Worker 脚本不存在：" + path);
    return path;
  }

  private String statusToMaterial(String status) {
    return switch (status) {
      case "PARSED" -> "ANALYZED";
      case "PARSED_PARTIAL", "ADAPTER_REQUIRED", "UNSUPPORTED" -> "ANALYSIS_PARTIAL";
      default -> "ANALYSIS_FAILED";
    };
  }

  private List<String> resultWarnings(String resultJson) {
    if (resultJson == null || resultJson.isBlank()) return List.of();
    try {
      Map<String, Object> result = json.readValue(resultJson, new TypeReference<>() { });
      Object warnings = result.get("warnings");
      if (!(warnings instanceof List<?> values)) return List.of();
      return values.stream().filter(Objects::nonNull).map(String::valueOf).filter(value -> !value.isBlank()).toList();
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private void cleanupRunRoot(Path root) {
    try {
      if (!Files.exists(root)) return;
      try (var stream = Files.walk(root)) { stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) { } }); }
    } catch (Exception ignored) { }
  }

  private AnalysisJob mapJob(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new AnalysisJob(rs.getObject("id", UUID.class), rs.getObject("material_id", UUID.class),
        rs.getObject("workflow_task_id", UUID.class), rs.getString("parser"), rs.getString("status"),
        rs.getString("result_json"), rs.getString("analysis_storage_key"), rs.getString("preview_storage_key"),
        rs.getString("error_code"), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private Fact mapFact(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Fact(rs.getObject("id", UUID.class), rs.getObject("material_id", UUID.class),
        rs.getObject("analysis_job_id", UUID.class), rs.getString("fact_name"), rs.getString("value_json"),
        rs.getString("unit"), rs.getString("source_ref"), rs.getDouble("confidence"), rs.getBoolean("verified"),
        rs.getBoolean("usable_for_generation"), rs.getString("verification_note"), rs.getObject("verified_by", UUID.class),
        rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toInstant(), rs.getTimestamp("created_at").toInstant());
  }

  private Annotation mapAnnotation(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Annotation(rs.getObject("id", UUID.class), rs.getObject("material_id", UUID.class),
        rs.getObject("analysis_job_id", UUID.class), rs.getString("annotation_kind"), rs.getObject("page_number", Integer.class),
        rs.getString("value_json"), rs.getString("source_ref"), rs.getDouble("confidence"), rs.getBoolean("verified"),
        rs.getBoolean("usable_for_generation"), rs.getString("verification_note"), rs.getObject("verified_by", UUID.class),
        rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toInstant(), rs.getTimestamp("created_at").toInstant());
  }

  private PreviewAsset mapPreviewAsset(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new PreviewAsset(rs.getObject("id", UUID.class), rs.getObject("material_id", UUID.class),
        rs.getObject("analysis_job_id", UUID.class), rs.getString("asset_type"),
        rs.getObject("page_number", Integer.class), rs.getString("media_type"),
        rs.getLong("size_bytes"), rs.getTimestamp("created_at").toInstant());
  }

  private Map<String, Object> toStringMap(Map<?, ?> value) {
    Map<String, Object> result = new LinkedHashMap<>();
    value.forEach((key, item) -> result.put(String.valueOf(key), item));
    return result;
  }

  private double number(Object value, double fallback) { return value instanceof Number number ? number.doubleValue() : fallback; }
  private String limit(String value, int max) { if (value == null) return null; return value.substring(0, Math.min(max, value.length())); }
  private String rootMessage(Throwable error) { Throwable value = error; while (value.getCause() != null) value = value.getCause(); return Objects.requireNonNullElse(value.getMessage(), value.getClass().getSimpleName()); }

  public record AnalysisJob(UUID id, UUID materialId, UUID workflowTaskId, String parser, String status,
      String resultJson, String analysisStorageKey, String previewStorageKey, String errorCode, String errorMessage,
      Instant createdAt, Instant updatedAt) { }
  public record Fact(UUID id, UUID materialId, UUID analysisJobId, String name, String valueJson, String unit,
      String sourceRef, double confidence, boolean verified, boolean usableForGeneration, String verificationNote,
      UUID verifiedBy, Instant verifiedAt, Instant createdAt) { }
  public record Annotation(UUID id, UUID materialId, UUID analysisJobId, String kind, Integer pageNumber,
      String valueJson, String sourceRef, double confidence, boolean verified, boolean usableForGeneration,
      String verificationNote, UUID verifiedBy, Instant verifiedAt, Instant createdAt) { }
  public record PreviewAsset(UUID id, UUID materialId, UUID analysisJobId, String assetType, Integer pageNumber,
      String mediaType, long sizeBytes, Instant createdAt) { }
  public record AnalysisView(CadMaterialService.Material material, AnalysisJob job, List<Fact> facts,
      List<Annotation> annotations, List<PreviewAsset> previewAssets, List<String> warnings) { }
}
