package com.tikuzhushou.project;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class QualityReviewPersistenceTests {
  JdbcTemplate jdbc;ExamProjectQuestionReviewService service;
  UUID project=UUID.randomUUID(),run=UUID.randomUUID(),item=UUID.randomUUID(),user=UUID.randomUUID();
  @BeforeEach void setup() throws Exception {
    var ds=new DriverManagerDataSource("jdbc:h2:mem:quality-review-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(ds);
    jdbc.execute("create table app_users(id uuid primary key,username varchar)");jdbc.update("insert into app_users values(?,'teacher')",user);
    jdbc.execute("create table exam_project_generation_runs(id uuid primary key,project_id uuid,status varchar,processed_count int,review_required_count int,review_pending_count int,failed_count int,updated_at timestamp)");
    jdbc.execute("create table exam_project_generation_items(id uuid primary key,run_id uuid,variant_item_id uuid,variant_no int,variant_label varchar,sequence_no int,question_type varchar,type_label varchar,difficulty varchar,points int,status varchar,question_json text,design_json text,evidence_json text,review_json text,error_code varchar,error_message varchar,updated_at timestamp)");
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V42__exam_project_question_review.sql")).execute(ds);
    jdbc.execute("alter table exam_project_question_review_events drop constraint if exists CONSTRAINT_BDB4");
    jdbc.execute("alter table exam_project_question_review_events add constraint exam_project_question_review_events_action_check check (action in ('EDITED','APPROVED','REJECTED','SAVED','REOPENED','RISK_ACCEPTED'))");
    jdbc.update("insert into exam_project_generation_runs(id,project_id) values(?,?)",run,project);
    jdbc.update("insert into exam_project_generation_items(id,run_id,variant_item_id,variant_no,variant_label,sequence_no,question_type,type_label,difficulty,points,status,question_json,design_json,evidence_json,review_json) values(?,?,?,1,'A',1,'SHORT_ANSWER','简答题','MEDIUM',2,'REVIEW_REQUIRED',?,?, '{}','{}')",item,run,UUID.randomUUID(),new ObjectMapper().writeValueAsString(Map.of("stem","说明投影图中如何确定空间关系。","answer","对应投影面比较","analysis","不同视图表达不同方向","scoringRubric","条件与结论各一分")),"{\"pipelineVersion\":\"OPEN_ASSESSMENT_V2\"}");
    var access=mock(KnowledgeBaseAccessService.class);when(access.currentUserId()).thenReturn(user);
    service=new ExamProjectQuestionReviewService(jdbc,new ObjectMapper().findAndRegisterModules(),mock(ExamProjectService.class),access);
  }
  ExamProjectQuestionReviewService.ReviewRequest request(String decision,int version,Map<String,Object> q){return new ExamProjectQuestionReviewService.ReviewRequest(decision,version,"测试说明",q);}
  @Test void v2CanApproveWithoutSourceExcerpt(){assertThat(service.review(project,run,item,request("APPROVE",1,null)).status()).isEqualTo("APPROVED");assertThat(jdbc.queryForObject("select status from exam_project_generation_runs where id=?",String.class,run)).isEqualTo("SUCCEEDED");}
  @Test void legacySourceRequirementPreserved(){jdbc.update("update exam_project_generation_items set design_json='{}' where id=?",item);assertThatThrownBy(()->service.review(project,run,item,request("APPROVE",1,null))).hasMessageContaining("资料来源");}
  @Test void incompleteDraftCanSaveButCannotApprove(){var saved=service.review(project,run,item,request("SAVE_DRAFT",1,Map.of("answer","")));assertThat(saved.questionVersion()).isEqualTo(2);assertThatThrownBy(()->service.review(project,run,item,request("APPROVE",2,null))).hasMessageContaining("答案为空");assertThat(service.versions(project,run,item)).hasSize(2);}
  @Test void staleEditFails(){service.review(project,run,item,request("SAVE_DRAFT",1,Map.of("answer","新答案")));assertThatThrownBy(()->service.review(project,run,item,request("SAVE_DRAFT",1,Map.of("answer","过期答案")))).hasMessageContaining("已被其他操作更新");}
  @Test void approvedQuestionMustBeReopened(){service.review(project,run,item,request("APPROVE",1,null));assertThatThrownBy(()->service.review(project,run,item,request("APPROVE",1,Map.of("answer","覆盖答案")))).hasMessageContaining("重新打开");assertThat(service.review(project,run,item,request("REOPEN",1,null)).status()).isEqualTo("REVIEW_REQUIRED");}
  @Test void wrongScoringSumCannotApprove(){assertThatThrownBy(()->service.review(project,run,item,request("APPROVE",1,Map.of("scoringItems",List.of(Map.of("criterion","正确作答","points",3)))))).hasMessageContaining("不一致");}
  @Test void structuredRubricIsAlsoCanonicalForExport(){var saved=service.review(project,run,item,request("SAVE_DRAFT",1,Map.of("scoringItems",List.of(Map.of("criterion","条件正确","points",2)),"scoringRubric","过期的文字评分")));assertThat(saved.question().get("scoringRubric")).isEqualTo("条件正确（2分）");}
  @Test void failedQuestionCanBeKeptWithExplicitRisk(){jdbc.update("update exam_project_generation_items set status='FAILED',error_code='QUALITY_REJECTED',error_message='AI 质量未通过',question_version=1,reviewer_id=null where id=?",item);var kept=service.keepOriginal(project,run,item,"人工确认保留");assertThat(kept.status()).isEqualTo("APPROVED_WITH_RISK");assertThat(jdbc.queryForObject("select status from exam_project_generation_runs where id=?",String.class,run)).isEqualTo("SUCCEEDED");assertThat(service.exportReadiness(project,run)).satisfies(readiness -> {assertThat(readiness.ready()).isTrue();assertThat(readiness.approvedCount()).isEqualTo(1);assertThat(readiness.riskAcceptedCount()).isEqualTo(1);});assertThat(jdbc.queryForObject("select action from exam_project_question_review_events where generation_item_id=?",String.class,item)).isEqualTo("RISK_ACCEPTED");}
  @Test void spoofedPipelineCannotBypassLegacyValidation(){jdbc.update("update exam_project_generation_items set design_json='{}' where id=?",item);assertThatThrownBy(()->service.review(project,run,item,request("APPROVE",1,Map.of("_assessmentPipelineVersion","OPEN_ASSESSMENT_V2")))).hasMessageContaining("资料来源");}
}
