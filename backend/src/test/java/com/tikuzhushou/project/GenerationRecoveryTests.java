package com.tikuzhushou.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import com.tikuzhushou.question.QuestionProfessionalReviewService;
import com.tikuzhushou.question.QuestionRefinementService;
import com.tikuzhushou.retrieval.RetrievalService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenerationRecoveryTests {
  private JdbcTemplate jdbc;
  private ExamProjectVariantGenerationService service;
  private final UUID project = UUID.randomUUID();
  private final UUID run = UUID.randomUUID();
  private final UUID keptItem = UUID.randomUUID();
  private final UUID failedItem = UUID.randomUUID();

  @BeforeEach
  void setup() throws Exception {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(
        "jdbc:h2:mem:generation-recovery-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("create table exam_project_generation_runs(id uuid primary key,project_id uuid,generation_task_id uuid,evidence_snapshot_id uuid,status varchar,generation_mode varchar,planned_count int,processed_count int,review_required_count int,review_pending_count int,failed_count int,error_message varchar,created_at timestamp,started_at timestamp,finished_at timestamp,updated_at timestamp)");
    jdbc.execute("create table exam_project_generation_items(id uuid primary key,run_id uuid,variant_item_id uuid,variant_no int,variant_label varchar,sequence_no int,question_type varchar,type_label varchar,difficulty varchar,points int,status varchar,attempts int,question_json text,evidence_json text,review_json text,error_code varchar,error_message varchar,question_version int,reviewer_id uuid,review_comment varchar,reviewed_at timestamp,updated_at timestamp)");
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    Instant now = Instant.now();
    jdbc.update("insert into exam_project_generation_runs(id,project_id,generation_task_id,evidence_snapshot_id,status,generation_mode,planned_count,processed_count,review_required_count,review_pending_count,failed_count,created_at,finished_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        run, project, UUID.randomUUID(), UUID.randomUUID(), "PARTIAL", "FAST", 2, 2, 1, 0, 1, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    jdbc.update("insert into exam_project_generation_items(id,run_id,variant_item_id,variant_no,variant_label,sequence_no,question_type,type_label,difficulty,points,status,attempts,question_json,evidence_json,review_json,question_version,updated_at) values(?,?,?,1,'A',1,'SINGLE_CHOICE','单选题','MEDIUM',2,'REVIEW_REQUIRED',1,?,'{}','{}',1,?)",
        keptItem, run, UUID.randomUUID(), json.writeValueAsString(Map.of("stem", "保留题目")), Timestamp.from(now));
    jdbc.update("insert into exam_project_generation_items(id,run_id,variant_item_id,variant_no,variant_label,sequence_no,question_type,type_label,difficulty,points,status,attempts,question_json,evidence_json,review_json,error_code,error_message,question_version,updated_at) values(?,?,?,1,'A',2,'SINGLE_CHOICE','单选题','MEDIUM',2,'FAILED',2,'{}','{}','{}','QUALITY_REJECTED','生成失败',1,?)",
        failedItem, run, UUID.randomUUID(), Timestamp.from(now));
    service = new ExamProjectVariantGenerationService(jdbc, json, mock(ExamProjectService.class),
        mock(ExamProjectGenerationPlanService.class), mock(ExamProjectEvidenceService.class),
        mock(QuestionRefinementService.class), mock(QuestionProfessionalReviewService.class),
        mock(KnowledgeBaseAccessService.class), mock(RetrievalService.class));
  }

  @Test
  void retryOnlyRequeuesTheFailedItem() {
    var result = service.retryFailedItem(project, run, failedItem);
    assertThat(result.status()).isEqualTo("QUEUED");
    assertThat(result.items()).filteredOn(item -> item.id().equals(failedItem)).singleElement()
        .extracting(ExamProjectVariantGenerationService.ItemView::status).isEqualTo("PLANNED");
    assertThat(result.items()).filteredOn(item -> item.id().equals(keptItem)).singleElement()
        .extracting(ExamProjectVariantGenerationService.ItemView::status).isEqualTo("REVIEW_REQUIRED");
  }

  @Test
  void removalMakesTheRemainingApprovedSetExportable() {
    jdbc.update("update exam_project_generation_items set status='APPROVED' where id=?", keptItem);
    var result = service.removeFailedItem(project, run, failedItem);
    assertThat(result.status()).isEqualTo("SUCCEEDED");
    assertThat(result.items()).filteredOn(item -> item.id().equals(failedItem)).singleElement()
        .extracting(ExamProjectVariantGenerationService.ItemView::status).isEqualTo("REMOVED");
  }
}
