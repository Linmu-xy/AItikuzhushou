package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.core.CoreBusinessService;
import com.tikuzhushou.fast.FastDocumentGenerationService;
import com.tikuzhushou.fast.FastDocumentGenerationWorker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Additive rapid document-to-bank API. Existing generation APIs are intentionally untouched. */
@RestController
@RequestMapping("/api/fast-document")
public class FastDocumentController {
  private final FastDocumentGenerationService service;
  private final FastDocumentGenerationWorker worker;
  private final AdminAuditService audit;

  public FastDocumentController(FastDocumentGenerationService service, FastDocumentGenerationWorker worker,
      AdminAuditService audit) {
    this.service = service; this.worker = worker; this.audit = audit;
  }

  @PostMapping("/jobs")
  ResponseEntity<FastDocumentGenerationService.JobView> create(
      @RequestBody FastDocumentGenerationService.CreateRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String headerKey) {
    FastDocumentGenerationService.CreateRequest effective = request == null ? null
        : new FastDocumentGenerationService.CreateRequest(request.documentId(), request.density(), request.difficulty(),
            request.questionTypes(), request.idempotencyKey() == null ? headerKey : request.idempotencyKey());
    var job = service.create(effective);
    worker.submit(job.id());
    audit.record("FAST_DOCUMENT_GENERATION_SUBMIT", AdminAuditService.TARGET_DOCUMENT, job.documentId(), null,
        Map.of("jobId", job.id(), "density", job.density(), "questionTypes", job.questionTypes()));
    return ResponseEntity.accepted().body(job);
  }

  @GetMapping("/jobs/{id}")
  FastDocumentGenerationService.JobView get(@PathVariable UUID id) { return service.get(id); }

  @GetMapping("/jobs/{id}/items")
  List<FastDocumentGenerationService.ItemView> items(@PathVariable UUID id) { return service.items(id); }

  @PostMapping("/jobs/{id}/retry")
  ResponseEntity<FastDocumentGenerationService.JobView> retry(@PathVariable UUID id) {
    var job = service.retry(id); worker.submit(id);
    audit.record("FAST_DOCUMENT_GENERATION_RETRY", AdminAuditService.TARGET_DOCUMENT, job.documentId(), null,
        Map.of("jobId", id));
    return ResponseEntity.accepted().body(job);
  }

  @PostMapping("/jobs/{id}/cancel")
  FastDocumentGenerationService.JobView cancel(@PathVariable UUID id) {
    var job = service.cancel(id);
    audit.record("FAST_DOCUMENT_GENERATION_CANCEL", AdminAuditService.TARGET_DOCUMENT, job.documentId(), null,
        Map.of("jobId", id));
    return job;
  }

  @PostMapping("/jobs/{id}/import")
  CoreBusinessService.Job importToQuestionBank(@PathVariable UUID id) {
    CoreBusinessService.Job job = service.importToQuestionBank(id);
    audit.record("FAST_DOCUMENT_GENERATION_IMPORT", AdminAuditService.TARGET_JOB, job.id(), null,
        Map.of("fastJobId", id));
    return job;
  }
}
