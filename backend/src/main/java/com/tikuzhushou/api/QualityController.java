package com.tikuzhushou.api;

import com.tikuzhushou.core.CoreBusinessService;
import com.tikuzhushou.question.QuestionProfessionalReviewService;
import com.tikuzhushou.question.QuestionContextAssetService;
import com.tikuzhushou.quality.QualityService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quality")
public class QualityController {
  private final QualityService quality;
  private final CoreBusinessService core;
  private final QuestionProfessionalReviewService professionalReviews;
  public QualityController(QualityService quality, CoreBusinessService core,
      QuestionProfessionalReviewService professionalReviews) {
    this.quality = quality; this.core = core; this.professionalReviews = professionalReviews;
  }
  @GetMapping("/jobs/{jobId}") QualityService.Report inspect(@PathVariable UUID jobId) { return quality.inspect(jobId); }
  @GetMapping("/jobs/{jobId}/preflight") QualityService.Preflight preflight(@PathVariable UUID jobId) {
    return quality.preflight(jobId);
  }
  @GetMapping("/jobs/{jobId}/professional-audits")
  List<QuestionProfessionalReviewService.Audit> professionalAudits(@PathVariable UUID jobId) {
    core.getJob(jobId); // Preserve the same job existence/authorization boundary as other quality endpoints.
    return professionalReviews.list(jobId);
  }
  @GetMapping("/jobs/{jobId}/professional-audits/summary")
  QuestionProfessionalReviewService.AuditSummary professionalAuditSummary(@PathVariable UUID jobId) {
    core.getJob(jobId);
    return professionalReviews.summary(jobId);
  }
  @GetMapping("/jobs/{jobId}/evidence-snapshot")
  List<QuestionContextAssetService.EvidenceSnapshot> evidenceSnapshot(@PathVariable UUID jobId) {
    return core.evidenceSnapshots(jobId);
  }
  @GetMapping("/jobs/{jobId}/generation-designs")
  List<CoreBusinessService.QuestionGenerationDesign> generationDesigns(@PathVariable UUID jobId) {
    return core.generationDesigns(jobId);
  }
}
