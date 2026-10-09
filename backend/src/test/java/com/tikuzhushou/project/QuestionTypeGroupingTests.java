package com.tikuzhushou.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QuestionTypeGroupingTests {
  @Test void recognizesCanonicalCodesAndCompleteChineseLabels() {
    assertThat(QuestionTypeOrder.canonical("SINGLE_CHOICE")).isEqualTo("SINGLE_CHOICE");
    assertThat(QuestionTypeOrder.canonical("填空题")).isEqualTo("FILL_BLANK");
    assertThat(QuestionTypeOrder.label("FILL_BLANK", "FILL_BLANK")).isEqualTo("填空题");
    assertThat(QuestionTypeOrder.label("ESSAY", "ESSAY")).isEqualTo("论述题");
    assertThat(QuestionTypeOrder.rank("CUSTOM", "绘图题")).isGreaterThan(QuestionTypeOrder.rank("PRACTICAL_TASK", "实操任务"));
    assertThat(QuestionTypeOrder.key("CUSTOM", "绘图题")).isNotEqualTo(QuestionTypeOrder.key("CUSTOM", "设计题"));
  }

  @Test void movesTheEntireIntelligentPlanSlotAndRenumbersWithoutLosingEvidence() {
    var original = plan();
    var grouped = ExamProjectGenerationPlanService.groupAssessmentPlan(original);
    assertThat(grouped.items()).extracting(KnowledgeAssessmentPlanningService.PlanItem::type)
        .containsExactly("SINGLE_CHOICE", "SINGLE_CHOICE", "FILL_BLANK", "SHORT_ANSWER");
    assertThat(grouped.items()).extracting(KnowledgeAssessmentPlanningService.PlanItem::sequence).containsExactly(1, 2, 3, 4);
    assertThat(grouped.items()).extracting(KnowledgeAssessmentPlanningService.PlanItem::task).containsExactly("题位2", "题位4", "题位3", "题位1");
    assertThat(grouped.items().getFirst().documentId()).isEqualTo(original.items().get(1).documentId());
    assertThat(grouped.items().getFirst().webEvidence()).isSameAs(original.items().get(1).webEvidence());
    assertThat(grouped.items().getFirst().visualSummary()).isEqualTo("原图2");
    assertThat(grouped.items().getFirst().points()).isEqualTo(4);
    assertThat(grouped.items().getFirst().difficulty()).isEqualTo("HARD");
    assertThat(grouped.domainBrief()).isEqualTo(original.domainBrief());
    assertThat(original.items().getFirst().sequence()).isEqualTo(1);
    assertThat(original.items().getFirst().type()).isEqualTo("SHORT_ANSWER");
  }

  @Test void groupsSpecifiedTypesForEachVariantAndPreservesCountsAndPoints() {
    Fixture fixture = fixture("CAREER", "[{\"type\":\"简答题\",\"count\":2,\"points\":5},{\"type\":\"填空题\",\"count\":1,\"points\":3},{\"type\":\"单选题\",\"count\":2,\"points\":2}]");
    var task = fixture.service.create(fixture.projectId);
    assertThat(task.orderingVersion()).isEqualTo(QuestionTypeOrder.VERSION);
    assertThat(task.items()).hasSize(10);
    for (int variant = 1; variant <= 2; variant++) {
      int number = variant;
      var items = task.items().stream().filter(item -> item.variantNo() == number).toList();
      assertThat(items).extracting(ExamProjectGenerationPlanService.VariantItemView::questionType)
          .containsExactly("SINGLE_CHOICE", "SINGLE_CHOICE", "FILL_BLANK", "SHORT_ANSWER", "SHORT_ANSWER");
      assertThat(items).extracting(ExamProjectGenerationPlanService.VariantItemView::sequenceNo).containsExactly(1, 2, 3, 4, 5);
      assertThat(items.stream().mapToInt(ExamProjectGenerationPlanService.VariantItemView::points).sum()).isEqualTo(17);
    }
    assertThat(fixture.service.create(fixture.projectId).id()).isEqualTo(task.id());
    verifyNoInteractions(fixture.planning);
  }

  @Test void upgradesLegacyPlanByForkingItWithoutAnotherAiCallOrChangingOriginalSlots() throws Exception {
    Fixture fixture = fixture("KNOWLEDGE_BASE", "[{\"type\":\"智能题型\",\"count\":4,\"points\":2}]");
    UUID legacyId = UUID.randomUUID();
    String request = fixture.json.writeValueAsString(Map.of("assessmentPlan", plan()));
    fixture.jdbc.update("insert into exam_project_generation_tasks values(?,?,?,'READY',2,4,8,?,?,current_timestamp,current_timestamp)",
        legacyId, fixture.projectId, fixture.snapshotId, request, UUID.randomUUID());
    fixture.jdbc.update("insert into exam_project_variant_items values(?,?,1,'A',1,'SHORT_ANSWER','简答题','MEDIUM',2,'[]','PLANNED',current_timestamp)", UUID.randomUUID(), legacyId);
    var updated = fixture.service.create(fixture.projectId);
    assertThat(updated.id()).isNotEqualTo(legacyId);
    assertThat(updated.items()).extracting(ExamProjectGenerationPlanService.VariantItemView::sequenceNo)
        .containsExactly(1, 2, 3, 4, 1, 2, 3, 4);
    assertThat(fixture.service.get(legacyId).items().getFirst().questionType()).isEqualTo("SHORT_ANSWER");
    assertThat(fixture.service.get(legacyId).assessmentPlan().items().getFirst().task()).isEqualTo("题位1");
    assertThat(fixture.service.get(legacyId).orderingVersion()).isEqualTo("LEGACY");
    assertThat(fixture.service.create(fixture.projectId).id()).isEqualTo(updated.id());
    verifyNoInteractions(fixture.planning);
  }

  private static KnowledgeAssessmentPlanningService.AssessmentPlan plan() {
    var types = List.of("SHORT_ANSWER", "SINGLE_CHOICE", "FILL_BLANK", "SINGLE_CHOICE");
    var items = java.util.stream.IntStream.range(0, types.size()).mapToObj(index ->
        new KnowledgeAssessmentPlanningService.PlanItem(index + 1, "目标" + index, "题位" + (index + 1), types.get(index),
            index == 1 ? "HARD" : "MEDIUM", index == 1 ? 4 : 2, UUID.randomUUID(), index + 1, "检索" + index,
            index == 1, "原图" + (index + 1), "PROVIDED_IMAGE", "SOURCE", "可作答",
            new KnowledgeAssessmentPlanningService.WebEvidence("主题" + index, "摘要" + index, List.of(), Instant.now()))).toList();
    return new KnowledgeAssessmentPlanningService.AssessmentPlan("考核", items, null,
        KnowledgeAssessmentPlanningService.OPEN_ASSESSMENT_VERSION, "全库语境", "学科说明");
  }

  private static Fixture fixture(String mode, String scores) {
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:type-plan-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("create table exam_project_generation_tasks(id uuid primary key,project_id uuid,evidence_snapshot_id uuid,status varchar,variant_count int,question_count_per_variant int,total_question_count int,request_json text,created_by uuid,created_at timestamp,updated_at timestamp)");
    jdbc.execute("create table exam_project_variant_items(id uuid primary key,generation_task_id uuid,variant_no int,variant_label varchar,sequence_no int,question_type varchar,type_label varchar,difficulty varchar,points int,source_roles_json text,status varchar,created_at timestamp)");
    UUID projectId = UUID.randomUUID(), snapshotId = UUID.randomUUID();
    var projects = mock(ExamProjectService.class);
    var evidence = mock(ExamProjectEvidenceService.class);
    var access = mock(KnowledgeBaseAccessService.class);
    var planning = mock(KnowledgeAssessmentPlanningService.class);
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    when(projects.get(projectId)).thenReturn(new ExamProjectService.ProjectView(projectId, UUID.randomUUID(), UUID.randomUUID(),
        "分组测试", mode, "DRAFT", "", 2, "{\"easy\":30,\"medium\":50,\"hard\":20}", scores, true, 0, Instant.now(), Instant.now(), List.of()));
    when(evidence.latest(projectId)).thenReturn(new ExamProjectEvidenceService.PreparationView(snapshotId, projectId, 1, "READY", List.of(), List.of(), 0, Map.of(), "hash", Instant.now()));
    when(access.currentUserId()).thenReturn(UUID.randomUUID());
    return new Fixture(jdbc, json, projectId, snapshotId, planning, new ExamProjectGenerationPlanService(jdbc, json, projects, evidence, access, planning));
  }
  private record Fixture(JdbcTemplate jdbc, ObjectMapper json, UUID projectId, UUID snapshotId,
      KnowledgeAssessmentPlanningService planning, ExamProjectGenerationPlanService service) { }
}
