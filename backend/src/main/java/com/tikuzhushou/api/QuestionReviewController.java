package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.review.QuestionReviewService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviews")
public class QuestionReviewController {
  private final QuestionReviewService reviews;
  private final AdminAuditService audit;
  public QuestionReviewController(QuestionReviewService reviews, AdminAuditService audit) {
    this.reviews = reviews; this.audit = audit;
  }

  @GetMapping("/jobs/{jobId}")
  List<QuestionReviewService.Item> list(@PathVariable UUID jobId,
      @RequestParam(defaultValue = "") String status) { return reviews.list(jobId, status); }
  @GetMapping("/jobs/{jobId}/summary")
  QuestionReviewService.Summary summary(@PathVariable UUID jobId) { return reviews.summary(jobId); }
  @PatchMapping("/jobs/{jobId}/items/{sequence}")
  QuestionReviewService.Item update(@PathVariable UUID jobId, @PathVariable int sequence,
      @RequestBody QuestionReviewService.Update request) {
    QuestionReviewService.Item updated = reviews.update(jobId, sequence, request);
    audit.record(AdminAuditService.REVIEW_UPDATE, AdminAuditService.TARGET_REVIEW, jobId, null,
        Map.of("sequence", sequence, "status", updated == null ? "" : updated.status(),
            "locked", updated != null && updated.locked()));
    return updated;
  }
  @GetMapping("/jobs/{jobId}/items/{sequence}/events")
  List<QuestionReviewService.Event> events(@PathVariable UUID jobId, @PathVariable int sequence) {
    return reviews.events(jobId, sequence);
  }
}
