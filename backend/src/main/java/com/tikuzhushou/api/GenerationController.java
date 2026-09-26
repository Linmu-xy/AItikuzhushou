package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.core.CoreBusinessService;
import com.tikuzhushou.core.GenerationWorker;
import com.tikuzhushou.workflow.GenerationProgressService;
import com.tikuzhushou.excel.BlueprintExcelService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class GenerationController {
  private final CoreBusinessService core;
  private final GenerationWorker worker;
  private final GenerationProgressService progress;
  private final AdminAuditService audit;
  private final BlueprintExcelService blueprintExcel;

  public GenerationController(CoreBusinessService core, GenerationWorker worker,
      GenerationProgressService progress, AdminAuditService audit, BlueprintExcelService blueprintExcel) {
    this.core = core;
    this.worker = worker;
    this.progress = progress;
    this.audit = audit; this.blueprintExcel = blueprintExcel;
  }

  @PostMapping("/occupational-standards/from-document/{documentId}")
  CoreBusinessService.Standard extract(@PathVariable UUID documentId) { return core.extractStandard(documentId); }
  @PostMapping("/occupational-standards/{id}/confirm")
  CoreBusinessService.Standard confirm(@PathVariable UUID id) {
    CoreBusinessService.Standard confirmed = core.confirm(id);
    audit.record(AdminAuditService.STANDARD_CONFIRM, AdminAuditService.TARGET_STANDARD, id,
        confirmed.profession(), Map.of("version", confirmed.version()));
    return confirmed;
  }
  @PutMapping("/occupational-standards/{id}")
  CoreBusinessService.Standard update(@PathVariable UUID id, @RequestBody StandardUpdate request) {
    CoreBusinessService.Standard updated = core.updateStandard(id, request.profession(), request.occupationCode(),
        request.levels(), request.assessmentPoints());
    audit.record(AdminAuditService.STANDARD_UPDATE, AdminAuditService.TARGET_STANDARD, id,
        request.profession(), Map.of("levels", request.levels() == null ? 0 : request.levels().size(),
            "assessmentPoints", request.assessmentPoints() == null ? 0 : request.assessmentPoints().size()));
    return updated;
  }
  @GetMapping("/occupational-standards/{id}")
  CoreBusinessService.Standard standard(@PathVariable UUID id) { return core.getStandard(id); }
  @GetMapping("/occupational-standards/{id}/versions")
  List<CoreBusinessService.StandardVersion> versions(@PathVariable UUID id) { return core.versions(id); }
  @GetMapping("/occupational-standards/{id}/question-presets")
  List<Map<String, Object>> questionPresets(@PathVariable UUID id) {
    return core.getStandard(id).levels().stream().map(core::levelPreset).toList();
  }

  @PostMapping("/generation/blueprints")
  Map<String, Object> blueprint(@RequestBody BlueprintRequest request) {
    var job = core.blueprint(request.standardId(), request.total(), request.types(), request.typeCounts(), request.level());
    audit.record(AdminAuditService.BLUEPRINT_CREATE, AdminAuditService.TARGET_BLUEPRINT, job.id(),
        null, Map.of("standardId", request.standardId(), "total", request.total()));
    return progress.enrich(job);
  }

  @PutMapping("/generation/blueprints/{id}")
  Map<String, Object> updateBlueprint(@PathVariable UUID id, @RequestBody BlueprintUpdate request) {
    var job = core.updateBlueprint(id, request.items());
    audit.record(AdminAuditService.BLUEPRINT_UPDATE, AdminAuditService.TARGET_BLUEPRINT, id, null,
        Map.of("items", request.items() == null ? 0 : request.items().size()));
    return progress.enrich(job);
  }

  @GetMapping("/generation/blueprints/{id}/export.xlsx")
  ResponseEntity<byte[]> exportBlueprint(@PathVariable UUID id) throws Exception {
    byte[] workbook = blueprintExcel.export(id);
    audit.record("BLUEPRINT_EXCEL_EXPORT", AdminAuditService.TARGET_BLUEPRINT, id, null, Map.of("bytes", workbook.length));
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=blueprint.xlsx")
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(workbook);
  }

  @PostMapping("/generation/questions/{blueprintJobId}")
  Map<String, Object> questions(@PathVariable UUID blueprintJobId) {
    var job = core.questions(blueprintJobId);
    audit.record(AdminAuditService.QUESTIONS_SUBMIT, AdminAuditService.TARGET_BLUEPRINT, blueprintJobId, null,
        Map.of("jobId", job.id()));
    return progress.enrich(job);
  }

  @PostMapping("/generation/questions/{blueprintJobId}/submit")
  ResponseEntity<Map<String, Object>> submitQuestions(@PathVariable UUID blueprintJobId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) QuestionSubmitRequest request) {
    var queued = core.queueQuestionsResult(blueprintJobId, idempotencyKey,
        request == null ? "SINGLE_REFILL" : request.refillPolicy(),
        request == null ? "FAST" : request.generationMode());
    if (queued.created()) worker.submit(queued.job().id());
    audit.record(AdminAuditService.QUESTIONS_SUBMIT, AdminAuditService.TARGET_BLUEPRINT, blueprintJobId, null,
        Map.of("jobId", queued.job().id(), "created", queued.created()));
    return ResponseEntity.accepted().body(progress.enrich(queued.job()));
  }

  @PostMapping("/generation/jobs/{id}/retry")
  ResponseEntity<Map<String, Object>> retry(@PathVariable UUID id) {
    var job = core.retry(id);
    worker.submit(job.id());
    audit.record(AdminAuditService.GENERATION_RETRY, AdminAuditService.TARGET_JOB, id, null, Map.of());
    return ResponseEntity.accepted().body(progress.enrich(job));
  }

  @PostMapping("/generation/jobs/{id}/cancel")
  Map<String, Object> cancel(@PathVariable UUID id) {
    var job = core.cancel(id);
    audit.record(AdminAuditService.GENERATION_CANCEL, AdminAuditService.TARGET_JOB, id, null, Map.of());
    return progress.enrich(job);
  }
  @GetMapping("/generation/jobs")
  List<Map<String, Object>> jobs(@RequestParam(defaultValue = "50") int limit) { return progress.enrich(core.listJobs(limit)); }
  @GetMapping("/generation/jobs/{id}")
  Map<String, Object> job(@PathVariable UUID id) { return progress.enrich(core.getJob(id)); }
  @GetMapping("/generation/jobs/{id}/items")
  List<Map<String, Object>> items(@PathVariable UUID id) { return core.generationItems(id); }

  @GetMapping("/generation/jobs/{id}/export.xlsx")
  ResponseEntity<byte[]> export(@PathVariable UUID id) throws Exception {
    byte[] workbook = core.workbook(id);
    audit.record(AdminAuditService.EXPORT_DOWNLOAD, AdminAuditService.TARGET_JOB, id, null, Map.of("bytes", workbook.length));
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=question-bank.xlsx")
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(workbook);
  }

  public record BlueprintRequest(UUID standardId, int total, List<String> types, Map<String, Integer> typeCounts, String level) { }
  public record BlueprintUpdate(List<Map<String, Object>> items) { }
  public record QuestionSubmitRequest(String refillPolicy, String generationMode) { }
  public record StandardUpdate(String profession, String occupationCode, List<String> levels,
      List<String> assessmentPoints) { }
}
