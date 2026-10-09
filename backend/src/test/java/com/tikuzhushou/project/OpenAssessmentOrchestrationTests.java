package com.tikuzhushou.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import com.tikuzhushou.question.OpenAssessmentService;
import com.tikuzhushou.question.QuestionProfessionalReviewService;
import com.tikuzhushou.question.QuestionRefinementService;
import com.tikuzhushou.retrieval.RetrievalService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenAssessmentOrchestrationTests {
  private JdbcTemplate jdbc;
  private final QuestionRefinementService refiner = mock(QuestionRefinementService.class);
  private final QuestionProfessionalReviewService reviewer = mock(QuestionProfessionalReviewService.class);
  private ExamProjectVariantGenerationService service;
  private UUID runId;

  @BeforeEach void setup() throws Exception {
    // Keep the database alive across the service's separate connections.
    jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:orchestration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("create table document_chunks(document_id uuid, chunk_index int, content clob)");
    jdbc.execute("create table exam_project_evidence_snapshots(id uuid,project_id uuid,snapshot_version int,status varchar,blockers_json clob,sources_json clob,facts_json clob,profiles_json clob,snapshot_hash varchar,created_at timestamp)");
    jdbc.execute("create table exam_project_variant_items(id uuid,generation_task_id uuid,variant_no int,variant_label varchar,sequence_no int,question_type varchar,type_label varchar,difficulty varchar,points int)");
    jdbc.execute("create table exam_project_generation_runs(id uuid,project_id uuid,generation_task_id uuid,evidence_snapshot_id uuid,status varchar,generation_mode varchar,planned_count int,processed_count int,review_required_count int,review_pending_count int,failed_count int,request_json clob,created_by uuid,created_at timestamp,started_at timestamp,finished_at timestamp,updated_at timestamp,error_message varchar)");
    jdbc.execute("create table exam_project_generation_items(id uuid,run_id uuid,variant_item_id uuid,variant_no int,variant_label varchar,sequence_no int,question_type varchar,type_label varchar,difficulty varchar,points int,status varchar,attempts int,question_json clob,design_json clob,evidence_json clob,review_json clob,error_code varchar,error_message varchar,created_at timestamp,updated_at timestamp,question_version int default 1,reviewer_id uuid,review_comment varchar,reviewed_at timestamp)");
    UUID projectId = UUID.randomUUID(), snapshotId = UUID.randomUUID(), taskId = UUID.randomUUID(), document = UUID.randomUUID();
    Instant now = Instant.now();
    var json = new ObjectMapper();
    var projects = mock(ExamProjectService.class);
    var plans = mock(ExamProjectGenerationPlanService.class);
    var evidence = mock(ExamProjectEvidenceService.class);
    var access = mock(KnowledgeBaseAccessService.class);
    var sources = List.<Map<String, Object>>of(Map.of("sourceId", document, "sourceType", "DOCUMENT", "sourceRole", "OTHER", "name", "学科资料"));
    var project = new ExamProjectService.ProjectView(projectId, UUID.randomUUID(), UUID.randomUUID(), "测试", "KNOWLEDGE_BASE", "DRAFT", "", 1, "{}", "[]", true, false, 1, now, now, List.of());
    var snapshot = new ExamProjectEvidenceService.PreparationView(snapshotId, projectId, 1, "READY", List.of(), sources, 0, Map.of(), "hash", now);
    var plan = new KnowledgeAssessmentPlanningService.AssessmentPlan("能力迁移", List.of(new KnowledgeAssessmentPlanningService.PlanItem(1, "比例计算", "自拟情境计算", "SHORT_ANSWER", "MEDIUM", 4, null, 0, "", false, "", "PROVIDED_TEXT", "GENERAL", "题干给定条件", null)), null, OpenAssessmentService.VERSION, "学科背景");
    var task = new ExamProjectGenerationPlanService.TaskView(taskId, projectId, snapshotId, "READY", 1, 1, 1, now, now, plan, List.of());
    when(projects.get(projectId)).thenReturn(project);
    when(plans.get(taskId)).thenReturn(task);
    when(evidence.latest(projectId)).thenReturn(snapshot);
    when(access.currentUserId()).thenReturn(project.ownerId());
    when(refiner.deliveryQuestion(anyMap())).thenAnswer(invocation -> {
      Map<String, Object> value = new LinkedHashMap<>(invocation.getArgument(0));
      value.keySet().removeIf(key -> key.startsWith("_")); return value;
    });
    jdbc.update("insert into exam_project_evidence_snapshots values(?,?,1,'READY','[]',?,'[]','{}','hash',?)", snapshotId, projectId, json.writeValueAsString(sources), Timestamp.from(now));
    jdbc.update("insert into exam_project_variant_items values(?,?,1,'A',1,'SHORT_ANSWER','简答题','MEDIUM',4)", UUID.randomUUID(), taskId);
    service = new ExamProjectVariantGenerationService(jdbc, json, projects, plans, evidence, refiner, reviewer, access, mock(RetrievalService.class));
    runId = service.create(projectId, taskId, "FAST").id();
  }

  @Test void semanticRejectionGetsOneRepairAndAnIndependentReviewAgain() {
    generate(true);
    when(reviewer.review(anyList())).thenReturn(List.of(review(false, true)), List.of(review(true, true)));
    service.execute(runId);
    assertThat(service.get(runId).items().getFirst().status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(service.get(runId).items().getFirst().attempts()).isEqualTo(2);
    verify(refiner, times(2)).generateCandidates(anyList(), eq("FAST"));
    verify(reviewer, times(2)).review(anyList());
  }

  @Test void unavailableReviewPreservesCandidateWithoutRegeneration() {
    generate(true);
    when(reviewer.review(anyList())).thenReturn(List.of(review(false, false)));
    service.execute(runId);
    assertThat(service.get(runId).items().getFirst().status()).isEqualTo("REVIEW_PENDING");
    assertThat(service.get(runId).items().getFirst().question().get("stem")).isEqualTo("完整可用的原题");
    verify(refiner, times(1)).generateCandidates(anyList(), eq("FAST"));
  }

  @Test void conflictingReviewDoesNotRegenerateAndKeepsDiagnosticFlags() {
    generate(true);
    when(reviewer.review(anyList())).thenReturn(List.of(new QuestionProfessionalReviewService.Review(
        1, false, 80, 10, List.of("REVIEW_PROTOCOL_CONFLICT", "PROFESSIONAL_REVIEW_UNAVAILABLE"),
        "审查响应结论矛盾，保留原题待复核", List.of(), false)));
    service.execute(runId);
    var item = service.get(runId).items().getFirst();
    assertThat(item.status()).isEqualTo("REVIEW_PENDING");
    assertThat(item.attempts()).isEqualTo(1);
    assertThat(item.question().get("stem")).isEqualTo("完整可用的原题");
    assertThat(jdbc.queryForObject("select review_json from exam_project_generation_items where run_id=?", String.class, runId))
        .contains("REVIEW_PROTOCOL_CONFLICT");
    verify(refiner, times(1)).generateCandidates(anyList(), eq("FAST"));
  }

  @Test void exhaustedProtocolRecoveryDoesNotEnterSemanticRewriteLoop() {
    when(refiner.generateCandidates(anyList(), eq("FAST"))).thenAnswer(invocation -> {
      List<Map<String,Object>> drafts = invocation.getArgument(0);
      var question = new LinkedHashMap<>(drafts.getFirst());
      question.put("_assessmentNoAutoRewrite", true);
      question.put("_assessmentExecution", List.of(Map.of("result", "TRUNCATED", "recovery", true)));
      return List.of(new QuestionRefinementService.Candidate(question, false, false, 0, "响应未完成"));
    });
    service.execute(runId);
    assertThat(service.get(runId).items().getFirst().status()).isEqualTo("FAILED");
    verify(refiner, times(1)).generateCandidates(anyList(), eq("FAST"));
    verifyNoInteractions(reviewer);
    assertThat(jdbc.queryForObject("select design_json from exam_project_generation_items where run_id=?", String.class, runId))
        .contains("Execution", "TRUNCATED");
  }

  @Test void restartDoesNotOverwriteAlreadyReviewedOrEditedItems() {
    generate(true);
    when(reviewer.review(anyList())).thenReturn(List.of(review(true, true)));
    service.execute(runId);
    jdbc.update("update exam_project_generation_items set status='APPROVED',question_json='{" + "\"stem\":\"人工编辑的题目\"}',question_version=2 where run_id=?", runId);
    jdbc.update("update exam_project_generation_runs set status='QUEUED' where id=?", runId);
    service.execute(runId);
    var item = service.get(runId).items().getFirst();
    assertThat(item.status()).isEqualTo("APPROVED");
    assertThat(item.questionVersion()).isEqualTo(2);
    assertThat(item.question().get("stem")).isEqualTo("人工编辑的题目");
    assertThat(service.get(runId).status()).isEqualTo("SUCCEEDED");
    verify(refiner, times(1)).generateCandidates(anyList(), eq("FAST"));
  }

  @Test void retryingUnavailableReviewDoesNotWriteANewQuestionOrVersion() {
    generate(true);
    when(reviewer.review(anyList())).thenReturn(List.of(review(false, false)), List.of(review(true, true)));
    service.execute(runId);
    var before = service.get(runId);
    var item = before.items().getFirst();
    var after = service.retryReview(before.projectId(), runId, item.id());
    assertThat(after.items().getFirst().status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(after.items().getFirst().question()).isEqualTo(item.question());
    assertThat(after.items().getFirst().questionVersion()).isEqualTo(item.questionVersion());
    verify(refiner, times(1)).generateCandidates(anyList(), eq("FAST"));
    verify(reviewer, times(2)).review(anyList());
  }

  @Test void reviewOnlyRetryRestoresSuccessfulRecoveryHistory() {
    generate(true);
    when(reviewer.review(anyList())).thenReturn(List.of(review(false, false)));
    service.execute(runId);
    var before = service.get(runId); var item = before.items().getFirst();
    jdbc.update("update exam_project_generation_items set design_json=? where id=?",
        "{\"Execution\":[{\"stage\":\"SOLVE\",\"recovery\":true,\"result\":\"RESPONSE_RECEIVED\"}],\"ExecutionVersion\":\"OLD\"}", item.id());
    when(reviewer.review(anyList())).thenAnswer(invocation -> {
      List<Map<String,Object>> questions = invocation.getArgument(0);
      assertThat(questions.getFirst().get("_assessmentExecution").toString()).contains("SOLVE", "RESPONSE_RECEIVED");
      return List.of(review(true, true));
    });
    service.retryReview(before.projectId(), runId, item.id());
    verify(refiner, times(1)).generateCandidates(anyList(), eq("FAST"));
  }

  @Test void failedRepairKeepsOriginalRejectedQuestionForHumanInspection() {
    generate(false);
    when(reviewer.review(anyList())).thenReturn(List.of(review(false, true)));
    service.execute(runId);
    var item = service.get(runId).items().getFirst();
    assertThat(item.status()).isEqualTo("REJECTED");
    assertThat(item.question().get("stem")).isEqualTo("完整可用的原题");
    assertThat(item.attempts()).isEqualTo(2);
  }

  @Test void automaticRejectionCanBeReviewedAgainWithoutRewritingTheCandidate() {
    generate(true);
    when(reviewer.review(anyList())).thenReturn(List.of(review(false, true)), List.of(review(false, true)), List.of(review(true, true)));
    service.execute(runId);
    var before = service.get(runId); var item = before.items().getFirst();
    var after = service.retryReview(before.projectId(), runId, item.id());
    assertThat(after.items().getFirst().status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(after.items().getFirst().question()).isEqualTo(item.question());
    assertThat(after.items().getFirst().questionVersion()).isEqualTo(item.questionVersion());
    assertThat(after.items().getFirst().attempts()).isEqualTo(2);
    assertThat(jdbc.queryForObject("select design_json from exam_project_generation_items where id=?", String.class, item.id()))
        .contains("ReviewRetries", "REJECTED");
    verify(refiner, times(2)).generateCandidates(anyList(), eq("FAST"));
  }

  @Test void humanDecisionCannotBeOverriddenByAutomaticReview() {
    generate(true); when(reviewer.review(anyList())).thenReturn(List.of(review(false, false)));
    service.execute(runId);
    var before = service.get(runId); var item = before.items().getFirst();
    jdbc.update("update exam_project_generation_items set reviewer_id=? where id=?", UUID.randomUUID(), item.id());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.retryReview(before.projectId(), runId, item.id()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("人工");
    verify(reviewer, times(1)).review(anyList());
  }

  @Test void editingDuringSlowReviewDoesNotLoseTheHumanVersion() {
    generate(true); when(reviewer.review(anyList())).thenReturn(List.of(review(false, false)));
    service.execute(runId);
    var before = service.get(runId); var item = before.items().getFirst();
    when(reviewer.review(anyList())).thenAnswer(invocation -> {
      jdbc.update("update exam_project_generation_items set status='APPROVED',question_json='{\"stem\":\"人工新版\"}',question_version=2,reviewer_id=? where id=?", UUID.randomUUID(), item.id());
      return List.of(review(true, true));
    });
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.retryReview(before.projectId(), runId, item.id()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未覆盖人工修改");
    var after = service.get(runId).items().getFirst();
    assertThat(after.status()).isEqualTo("APPROVED");
    assertThat(after.questionVersion()).isEqualTo(2);
    assertThat(after.question().get("stem")).isEqualTo("人工新版");
  }

  private void generate(boolean repairValid) {
    when(refiner.generateCandidates(anyList(), eq("FAST"))).thenAnswer(invocation -> {
      List<Map<String, Object>> drafts = invocation.getArgument(0);
      var question = new LinkedHashMap<>(drafts.getFirst());
      boolean repair = question.containsKey("retryFeedback");
      question.put("stem", repair ? "修订后的题目" : "完整可用的原题");
      question.put("answer", "学科解答"); question.put("analysis", "推导说明");
      return List.of(new QuestionRefinementService.Candidate(question, !repair || repairValid, true, 80, repairValid ? null : "修订失败"));
    });
  }
  private static QuestionProfessionalReviewService.Review review(boolean pass, boolean available) {
    return new QuestionProfessionalReviewService.Review(1, pass, 80, 10, pass ? List.of() : List.of("AMBIGUOUS"), pass ? "" : "补全给定条件", List.of(), available);
  }
}
