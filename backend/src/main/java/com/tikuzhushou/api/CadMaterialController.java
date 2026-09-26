package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.cad.CadAnalysisService;
import com.tikuzhushou.cad.CadAnalysisWorker;
import com.tikuzhushou.cad.CadMaterialService;
import com.tikuzhushou.workflow.WorkflowTaskService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** API surface for the new material-based model/drawing flow. */
@RestController
@RequestMapping("/api/cad-materials")
public class CadMaterialController {
  private final CadMaterialService materials;
  private final CadAnalysisService analysis;
  private final CadAnalysisWorker worker;
  private final AdminAuditService audit;

  public CadMaterialController(CadMaterialService materials, CadAnalysisService analysis,
      CadAnalysisWorker worker, AdminAuditService audit) {
    this.materials = materials;
    this.analysis = analysis;
    this.worker = worker;
    this.audit = audit;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Map<String, Object> upload(@RequestParam UUID knowledgeBaseId, @RequestPart MultipartFile file) throws Exception {
    CadMaterialService.Material material = materials.upload(knowledgeBaseId, file);
    audit.record("CAD_MATERIAL_UPLOAD", "CAD_MATERIAL", material.id(), material.originalName(),
        Map.of("knowledgeBaseId", knowledgeBaseId, "format", material.format(), "sizeBytes", material.sizeBytes()));
    return Map.of("material", material, "statusCode", "UPLOADED");
  }

  @GetMapping
  List<CadMaterialService.Material> list(@RequestParam UUID knowledgeBaseId) { return materials.list(knowledgeBaseId); }

  @GetMapping("/{id}")
  CadMaterialService.Material get(@PathVariable UUID id) { return materials.get(id); }

  @PostMapping("/{id}/analysis-jobs")
  ResponseEntity<WorkflowTaskService.Task> analyze(@PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    WorkflowTaskService.Task task = worker.submit(id, idempotencyKey);
    audit.record("CAD_ANALYSIS_SUBMIT", "CAD_MATERIAL", id, null, Map.of("taskId", task.id()));
    return ResponseEntity.accepted().body(task);
  }

  @GetMapping("/{id}/analysis")
  CadAnalysisService.AnalysisView analysis(@PathVariable UUID id) { return analysis.latest(id); }

  @PatchMapping("/facts/{factId}")
  CadAnalysisService.Fact verifyFact(@PathVariable UUID factId, @RequestBody FactVerification request) {
    CadAnalysisService.Fact fact = analysis.verifyFact(factId, request.verified(), request.usableForGeneration(), request.note());
    audit.record("CAD_FACT_VERIFY", "CAD_FACT", fact.id(), fact.name(),
        Map.of("verified", fact.verified(), "usableForGeneration", fact.usableForGeneration()));
    return fact;
  }

  @GetMapping("/{id}/download")
  ResponseEntity<Resource> download(@PathVariable UUID id) throws Exception {
    CadMaterialService.Material material = materials.get(id);
    Path path = materials.materialize(material);
    return resource(path, material.mediaType(), material.originalName());
  }

  @GetMapping(value = "/{id}/preview", produces = "text/plain")
  ResponseEntity<Resource> preview(@PathVariable UUID id) throws Exception {
    Path path = analysis.materializeAsset(id, "OBJ");
    return resource(path, "text/plain", id + ".obj");
  }

  private ResponseEntity<Resource> resource(Path path, String mediaType, String filename) throws Exception {
    if (!Files.isRegularFile(path)) throw new IllegalArgumentException("文件不存在");
    MediaType type;
    try { type = MediaType.parseMediaType(mediaType); } catch (Exception ignored) { type = MediaType.APPLICATION_OCTET_STREAM; }
    InputStreamResource resource = new InputStreamResource(Files.newInputStream(path));
    return ResponseEntity.ok().contentType(type).contentLength(Files.size(path))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
        .body(resource);
  }

  public record FactVerification(boolean verified, boolean usableForGeneration, String note) { }
}
