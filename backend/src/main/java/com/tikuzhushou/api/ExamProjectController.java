package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.project.ExamProjectService;
import com.tikuzhushou.project.ExamProjectEvidenceService;
import com.tikuzhushou.project.ExamProjectGenerationPlanService;
import com.tikuzhushou.project.ExamProjectVariantGenerationService;
import com.tikuzhushou.project.ExamProjectVariantGenerationWorker;
import com.tikuzhushou.project.ExamProjectQuestionReviewService;
import com.tikuzhushou.project.ExamProjectExportService;
import com.tikuzhushou.project.ExamProjectExportWorker;
import com.tikuzhushou.project.KnowledgeVisualService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exam-projects")
public class ExamProjectController {
  private final ExamProjectService projects;
  private final ExamProjectEvidenceService evidence;
  private final ExamProjectGenerationPlanService plans;
  private final ExamProjectVariantGenerationService generation;
  private final ExamProjectVariantGenerationWorker generationWorker;
  private final ExamProjectQuestionReviewService questionReview;
  private final ExamProjectExportService exports;
  private final ExamProjectExportWorker exportWorker;
  private final AdminAuditService audit;
  private final KnowledgeVisualService visuals;

  public ExamProjectController(ExamProjectService projects, ExamProjectEvidenceService evidence,
      ExamProjectGenerationPlanService plans, ExamProjectVariantGenerationService generation,
      ExamProjectVariantGenerationWorker generationWorker, ExamProjectQuestionReviewService questionReview,
      ExamProjectExportService exports, ExamProjectExportWorker exportWorker, AdminAuditService audit,
      KnowledgeVisualService visuals) {
    this.projects = projects; this.evidence = evidence;
    this.plans = plans;
    this.generation = generation;
    this.generationWorker = generationWorker;
    this.questionReview = questionReview;
    this.exports = exports;
    this.exportWorker = exportWorker;
    this.audit = audit;
    this.visuals = visuals;
  }

  @GetMapping(value = "/{id}/visuals/{documentId}/pages/{page}/image", produces = MediaType.IMAGE_PNG_VALUE)
  ResponseEntity<byte[]> visual(@PathVariable UUID id, @PathVariable UUID documentId, @PathVariable int page,
      @RequestParam(defaultValue = "0") int x, @RequestParam(defaultValue = "0") int y,
      @RequestParam(defaultValue = "100") int width, @RequestParam(defaultValue = "100") int height) {
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
        .body(visuals.page(id, documentId, page, x, y, width, height));
  }

  @GetMapping
  List<ExamProjectService.ProjectView> list() { return projects.list(); }

  @GetMapping("/task-center")
  TaskCenterView taskCenter() {
    return new TaskCenterView(projects.list(), generation.listSummaries(), exports.listSummaries());
  }

  @GetMapping("/generation-runs")
  List<ExamProjectVariantGenerationService.RunView> listGenerationRunSummaries() {
    return generation.listSummaries();
  }

  @GetMapping("/{id}")
  ExamProjectService.ProjectView get(@PathVariable UUID id) { return projects.get(id); }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  ExamProjectService.ProjectView create(@RequestBody ExamProjectService.CreateRequest request) {
    ExamProjectService.ProjectView value = projects.create(request);
    audit.record("EXAM_PROJECT_CREATE", "EXAM_PROJECT", value.id(), value.name(), Map.of("mode", value.mode(), "variantCount", value.variantCount()));
    return value;
  }

  @PatchMapping("/{id}")
  ExamProjectService.ProjectView update(@PathVariable UUID id, @RequestBody ExamProjectService.UpdateRequest request) {
    ExamProjectService.ProjectView value = projects.update(id, request);
    audit.record("EXAM_PROJECT_UPDATE", "EXAM_PROJECT", value.id(), value.name(), Map.of("mode", value.mode(), "variantCount", value.variantCount()));
    return value;
  }

  @PostMapping("/{id}/sources")
  ExamProjectService.SourceView addSource(@PathVariable UUID id, @RequestBody ExamProjectService.SourceRequest request) {
    ExamProjectService.SourceView value = projects.addSource(id, request);
    audit.record("EXAM_PROJECT_SOURCE_ADD", "EXAM_PROJECT", id, value.name(), Map.of("sourceType", value.sourceType(), "sourceRole", value.sourceRole()));
    return value;
  }

  @DeleteMapping("/{id}/sources/{sourceId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void removeSource(@PathVariable UUID id, @PathVariable UUID sourceId) {
    projects.removeSource(id, sourceId);
    audit.record("EXAM_PROJECT_SOURCE_REMOVE", "EXAM_PROJECT", id, null, Map.of("sourceId", sourceId));
  }

  @GetMapping("/{id}/generation-preparation")
  ResponseEntity<ExamProjectEvidenceService.PreparationView> generationPreparation(@PathVariable UUID id) {
    var value = evidence.latest(id);
    return value == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(value);
  }

  @PostMapping("/{id}/generation-preparation")
  ExamProjectEvidenceService.PreparationView prepareGeneration(@PathVariable UUID id) {
    ExamProjectEvidenceService.PreparationView value = evidence.prepare(id);
    audit.record("EXAM_PROJECT_EVIDENCE_FREEZE", "EXAM_PROJECT", id, null,
        Map.of("snapshotId", value.snapshotId(), "version", value.version(), "status", value.status(), "blockers", value.blockers().size()));
    return value;
  }

  @GetMapping("/{id}/variant-generation-tasks")
  List<ExamProjectGenerationPlanService.TaskView> listVariantGenerationTasks(@PathVariable UUID id) {
    return plans.list(id);
  }

  @PostMapping("/{id}/variant-generation-tasks")
  ExamProjectGenerationPlanService.TaskView createVariantGenerationTask(@PathVariable UUID id) {
    ExamProjectGenerationPlanService.TaskView value = plans.create(id);
    audit.record("EXAM_PROJECT_VARIANT_PLAN_CREATE", "EXAM_PROJECT", id, null,
        Map.of("taskId", value.id(), "snapshotId", value.evidenceSnapshotId(),
            "variantCount", value.variantCount(), "totalQuestionCount", value.totalQuestionCount()));
    return value;
  }

  @GetMapping("/{id}/variant-generation-tasks/{taskId}")
  ExamProjectGenerationPlanService.TaskView getVariantGenerationTask(@PathVariable UUID id, @PathVariable UUID taskId) {
    ExamProjectGenerationPlanService.TaskView value = plans.get(taskId);
    if (!value.projectId().equals(id)) throw new IllegalArgumentException("变式题位计划不属于当前项目");
    return value;
  }

  @GetMapping("/{id}/variant-generation-runs")
  List<ExamProjectVariantGenerationService.RunView> listVariantGenerationRuns(@PathVariable UUID id) {
    return generation.list(id);
  }

  @PostMapping("/{id}/variant-generation-tasks/{taskId}/runs")
  ResponseEntity<ExamProjectVariantGenerationService.RunView> startVariantGeneration(
      @PathVariable UUID id, @PathVariable UUID taskId, @RequestBody(required = false) GenerateRequest request) {
    ExamProjectVariantGenerationService.RunView value = generation.create(id, taskId,
        request == null ? "PROFESSIONAL_PRO" : request.generationMode());
    generationWorker.submit(value.id());
    audit.record("EXAM_PROJECT_VARIANT_GENERATION_START", "EXAM_PROJECT", id, null,
        Map.of("runId", value.id(), "taskId", taskId, "generationMode", value.generationMode(), "plannedCount", value.plannedCount()));
    return ResponseEntity.accepted().body(value);
  }

  @GetMapping("/{id}/variant-generation-runs/{runId}")
  ExamProjectVariantGenerationService.RunView getVariantGenerationRun(@PathVariable UUID id, @PathVariable UUID runId) {
    ExamProjectVariantGenerationService.RunView value = generation.get(runId);
    if (!value.projectId().equals(id)) throw new IllegalArgumentException("生成任务不属于当前项目");
    return value;
  }

  @PatchMapping("/{id}/variant-generation-runs/{runId}/items/{itemId}/review")
  ExamProjectQuestionReviewService.ReviewItem reviewVariantQuestion(@PathVariable UUID id, @PathVariable UUID runId,
      @PathVariable UUID itemId, @RequestBody ExamProjectQuestionReviewService.ReviewRequest request) {
    ExamProjectQuestionReviewService.ReviewItem value = questionReview.review(id, runId, itemId, request);
    audit.record("EXAM_PROJECT_QUESTION_REVIEW", "EXAM_PROJECT", id, null,
        Map.of("runId", runId, "itemId", itemId, "decision", request == null ? "" : request.decision(),
            "status", value.status(), "questionVersion", value.questionVersion()));
    return value;
  }

  @PostMapping("/{id}/variant-generation-runs/{runId}/items/{itemId}/retry-review")
  ExamProjectVariantGenerationService.RunView retryVariantReview(@PathVariable UUID id,
      @PathVariable UUID runId, @PathVariable UUID itemId) {
    var value = generation.retryReview(id, runId, itemId);
    audit.record("EXAM_PROJECT_REVIEW_RETRY", "EXAM_PROJECT", id, null, Map.of("runId", runId, "itemId", itemId));
    return value;
  }

  @PostMapping("/{id}/variant-generation-runs/{runId}/items/{itemId}/retry-generation")
  ResponseEntity<ExamProjectVariantGenerationService.RunView> retryVariantGeneration(@PathVariable UUID id,
      @PathVariable UUID runId, @PathVariable UUID itemId) {
    var value = generation.retryFailedItem(id, runId, itemId);
    generationWorker.submit(value.id());
    audit.record("EXAM_PROJECT_QUESTION_GENERATION_RETRY", "EXAM_PROJECT", id, null,
        Map.of("runId", runId, "itemId", itemId));
    return ResponseEntity.accepted().body(value);
  }

  @PostMapping("/{id}/variant-generation-runs/{runId}/items/{itemId}/remove")
  ExamProjectVariantGenerationService.RunView removeFailedVariantQuestion(@PathVariable UUID id,
      @PathVariable UUID runId, @PathVariable UUID itemId) {
    var value = generation.removeFailedItem(id, runId, itemId);
    audit.record("EXAM_PROJECT_QUESTION_REMOVE", "EXAM_PROJECT", id, null,
        Map.of("runId", runId, "itemId", itemId));
    return value;
  }

  @PostMapping("/{id}/variant-generation-runs/{runId}/items/{itemId}/keep-original")
  ExamProjectQuestionReviewService.ReviewItem keepOriginalVariantQuestion(@PathVariable UUID id,
      @PathVariable UUID runId, @PathVariable UUID itemId,
      @RequestBody(required = false) KeepOriginalRequest request) {
    var value = questionReview.keepOriginal(id, runId, itemId, request == null ? null : request.comment());
    audit.record("EXAM_PROJECT_QUESTION_KEEP_ORIGINAL", "EXAM_PROJECT", id, null,
        Map.of("runId", runId, "itemId", itemId, "status", value.status(), "questionVersion", value.questionVersion()));
    return value;
  }

  @GetMapping("/{id}/variant-generation-runs/{runId}/items/{itemId}/versions")
  List<ExamProjectQuestionReviewService.QuestionVersion> questionVersions(@PathVariable UUID id, @PathVariable UUID runId,
      @PathVariable UUID itemId) {
    return questionReview.versions(id, runId, itemId);
  }

  @GetMapping("/{id}/variant-generation-runs/{runId}/items/{itemId}/review-events")
  List<ExamProjectQuestionReviewService.ReviewEvent> questionReviewEvents(@PathVariable UUID id, @PathVariable UUID runId,
      @PathVariable UUID itemId) {
    return questionReview.events(id, runId, itemId);
  }

  @GetMapping("/{id}/variant-generation-runs/{runId}/export-readiness")
  ExamProjectQuestionReviewService.ExportReadiness exportReadiness(@PathVariable UUID id, @PathVariable UUID runId) {
    return questionReview.exportReadiness(id, runId);
  }

  record KeepOriginalRequest(String comment) { }

  @GetMapping("/{id}/variant-generation-runs/{runId}/exports")
  List<ExamProjectExportService.ExportRunView> listExports(@PathVariable UUID id, @PathVariable UUID runId) {
    return exports.list(id, runId);
  }

  @PostMapping("/{id}/variant-generation-runs/{runId}/exports")
  ResponseEntity<ExamProjectExportService.ExportRunView> startExport(@PathVariable UUID id, @PathVariable UUID runId,
      @RequestBody(required = false) ExamProjectExportService.CreateRequest request) {
    ExamProjectExportService.ExportRunView value = exports.create(id, runId, request);
    exportWorker.submit(value.id());
    audit.record("EXAM_PROJECT_EXPORT_START", "EXAM_PROJECT", id, null,
        Map.of("exportId", value.id(), "runId", runId, "outputCount", value.requestedCount()));
    return ResponseEntity.accepted().body(value);
  }

  @GetMapping("/{id}/variant-generation-runs/{runId}/exports/{exportId}")
  ExamProjectExportService.ExportRunView getExport(@PathVariable UUID id, @PathVariable UUID runId,
      @PathVariable UUID exportId) {
    ExamProjectExportService.ExportRunView value = exports.get(exportId);
    if (!value.projectId().equals(id) || !value.generationRunId().equals(runId)) throw new IllegalArgumentException("导出任务不属于当前生成批次");
    return value;
  }

  @GetMapping("/{id}/variant-generation-runs/{runId}/exports/{exportId}/artifacts/{artifactId}")
  ResponseEntity<byte[]> downloadExport(@PathVariable UUID id, @PathVariable UUID runId, @PathVariable UUID exportId,
      @PathVariable UUID artifactId) throws Exception {
    ExamProjectExportService.ExportRunView run = exports.get(exportId);
    if (!run.projectId().equals(id) || !run.generationRunId().equals(runId)) throw new IllegalArgumentException("导出任务不属于当前生成批次");
    ExamProjectExportService.StoredArtifact artifact = exports.artifact(artifactId);
    if (!artifact.exportRunId().equals(exportId)) throw new IllegalArgumentException("导出文件不属于当前导出任务");
    audit.record("EXAM_PROJECT_EXPORT_DOWNLOAD", "EXAM_PROJECT", id, artifact.filename(),
        Map.of("exportId", exportId, "artifactId", artifactId, "outputType", artifact.outputType(), "sizeBytes", artifact.sizeBytes()));
    String disposition = ContentDisposition.attachment().filename(artifact.filename(), StandardCharsets.UTF_8).build().toString();
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition)
        .header(HttpHeaders.CONTENT_LENGTH, Long.toString(artifact.sizeBytes()))
        .header(HttpHeaders.CONTENT_TYPE, artifact.mediaType()).body(exports.read(artifact));
  }

  public record GenerateRequest(String generationMode) { }
  public record TaskCenterView(List<ExamProjectService.ProjectView> projects,
      List<ExamProjectVariantGenerationService.RunView> generationRuns,
      List<ExamProjectExportService.ExportRunView> exports) { }
}
