package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.workflow.BusinessWorkflowWorker;
import com.tikuzhushou.workflow.WorkflowTaskService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BusinessWorkflowController {
  private final BusinessWorkflowWorker worker;
  private final AdminAuditService audit;
  public BusinessWorkflowController(BusinessWorkflowWorker worker, AdminAuditService audit) {
    this.worker = worker; this.audit = audit;
  }

  @PostMapping("/occupational-standards/from-document/{documentId}/submit")
  ResponseEntity<WorkflowTaskService.Task> submitStandard(@PathVariable UUID documentId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    WorkflowTaskService.Task task = worker.submitStandard(documentId, idempotencyKey);
    audit.record(AdminAuditService.STANDARD_EXTRACT_SUBMIT, AdminAuditService.TARGET_DOCUMENT, documentId, null,
        Map.of("taskId", task.id()));
    return ResponseEntity.accepted().body(task);
  }

  @PostMapping("/occupational-levels/from-document/{documentId}/submit")
  ResponseEntity<WorkflowTaskService.Task> discoverLevels(@PathVariable UUID documentId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    WorkflowTaskService.Task task = worker.submitLevelDiscovery(documentId, idempotencyKey);
    audit.record("LEVEL_DISCOVERY_SUBMIT", AdminAuditService.TARGET_DOCUMENT, documentId, null, Map.of("taskId", task.id()));
    return ResponseEntity.accepted().body(task);
  }

  @PostMapping("/occupational-levels/{discoveryId}/submit")
  ResponseEntity<WorkflowTaskService.Task> extractLevelStandard(@PathVariable UUID discoveryId, @RequestParam String level,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    WorkflowTaskService.Task task = worker.submitLevelStandard(discoveryId, level, idempotencyKey);
    audit.record("LEVEL_STANDARD_EXTRACT_SUBMIT", AdminAuditService.TARGET_STANDARD, discoveryId, level, Map.of("taskId", task.id()));
    return ResponseEntity.accepted().body(task);
  }

  @PostMapping("/generation/jobs/{jobId}/export-jobs")
  ResponseEntity<WorkflowTaskService.Task> submitExport(@PathVariable UUID jobId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    WorkflowTaskService.Task task = worker.submitExport(jobId, idempotencyKey);
    audit.record(AdminAuditService.EXPORT_SUBMIT, AdminAuditService.TARGET_JOB, jobId, null,
        Map.of("taskId", task.id()));
    return ResponseEntity.accepted().body(task);
  }

  @GetMapping("/export-artifacts/{artifactId}")
  ResponseEntity<byte[]> download(@PathVariable UUID artifactId) throws Exception {
    var artifact = worker.artifact(artifactId);
    String disposition = ContentDisposition.attachment()
        .filename(artifact.filename(), StandardCharsets.UTF_8).build().toString();
    audit.record(AdminAuditService.EXPORT_DOWNLOAD, AdminAuditService.TARGET_ARTIFACT, artifactId,
        artifact.filename(), Map.of("partial", artifact.partial(), "sizeBytes", artifact.sizeBytes()));
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition)
        .header("X-Tiku-Partial-Export", Boolean.toString(artifact.partial()))
        .header(HttpHeaders.CONTENT_LENGTH, Long.toString(artifact.sizeBytes()))
        .header(HttpHeaders.CONTENT_TYPE, artifact.mediaType()).body(worker.read(artifact));
  }
}
