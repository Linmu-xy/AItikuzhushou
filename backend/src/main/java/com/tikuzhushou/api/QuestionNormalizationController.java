package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.core.CoreBusinessService;
import com.tikuzhushou.fast.QuestionNormalizationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Additive Word/Excel question-bank normalization API. */
@RestController
@RequestMapping("/api/question-normalization")
public class QuestionNormalizationController {
  private final QuestionNormalizationService service;
  private final AdminAuditService audit;

  public QuestionNormalizationController(QuestionNormalizationService service, AdminAuditService audit) {
    this.service = service;
    this.audit = audit;
  }

  @PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<QuestionNormalizationService.JobView> create(@RequestParam("file") MultipartFile file) throws Exception {
    var job = service.create(file);
    audit.record("QUESTION_NORMALIZATION_SUBMIT", AdminAuditService.TARGET_JOB, job.id(), null,
        Map.of("fileName", job.originalName(), "mediaType", job.mediaType()));
    return ResponseEntity.accepted().body(job);
  }

  @GetMapping("/jobs/{id}")
  QuestionNormalizationService.JobView get(@PathVariable UUID id) { return service.get(id); }

  @GetMapping("/jobs/{id}/items")
  List<QuestionNormalizationService.ItemView> items(@PathVariable UUID id) { return service.items(id); }

  @PostMapping("/jobs/{id}/confirm")
  CoreBusinessService.Job confirm(@PathVariable UUID id) {
    CoreBusinessService.Job job = service.confirm(id);
    audit.record("QUESTION_NORMALIZATION_IMPORT", AdminAuditService.TARGET_JOB, job.id(), null,
        Map.of("normalizationJobId", id));
    return job;
  }
}
