package com.tikuzhushou.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.ai.DeepSeekService;
import com.tikuzhushou.assistant.DeepSeekWebSearchService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeAssessmentMaterialRecoveryTests {
  @Test void replacesOnlyUnsupportedSlotsAndPreservesCountGoalsDifficultyAndPoints() throws Exception {
    var good = slot(1, "解释视图关系", "PROVIDED_TEXT");
    var bad = slot(2, "修改给定DWG文件并提交", "EXTERNAL_ARTIFACT");
    var replacement = slot(2, "依据题干说明视图布局步骤", "PROVIDED_TEXT");
    replacement.put("competency", "不允许改变的目标"); replacement.put("points", 1); replacement.put("difficulty", "EASY");
    var fixture = fixture(List.of(good, bad), List.of(replacement));
    var plan = fixture.plan(List.of());
    assertThat(plan.items()).hasSize(2);
    assertThat(plan.items().getFirst().task()).isEqualTo("解释视图关系");
    var repaired = plan.items().get(1);
    assertThat(repaired.sequence()).isEqualTo(2);
    assertThat(repaired.competency()).isEqualTo("能力2");
    assertThat(repaired.difficulty()).isEqualTo("HARD");
    assertThat(repaired.points()).isEqualTo(5);
    assertThat(repaired.requiredMaterial()).isEqualTo("PROVIDED_TEXT");
    assertThat(plan.summary()).contains("自动调整 1");
    var prompt = ArgumentCaptor.forClass(String.class);
    verify(fixture.ai).analyseJsonFast(anyString(), prompt.capture(), eq("ASSESSMENT_PLAN_MATERIAL_RECOVERY"));
    var input = new ObjectMapper().readTree(prompt.getValue().substring(prompt.getValue().indexOf("{\"requirementText\"")));
    assertThat(input.path("slotsToReplace").size()).isEqualTo(1);
    assertThat(input.path("slotsToReplace").get(0).path("original").path("sequence").asInt()).isEqualTo(2);
    assertThat(input.path("retainedSlotsDoNotChange").get(0).path("sequence").asInt()).isEqualTo(1);
    verify(fixture.ai, times(1)).analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_PLAN"));
  }

  @Test void groupsAllUnsupportedSlotsIntoOneRecoveryAndMergesBySequenceNotResponseOrder() throws Exception {
    var original = List.of(slot(1, "解释视图", "PROVIDED_TEXT"),
        slot(2, "编辑给定DXF文件", "PROVIDED_TEXT"), slot(3, "操作外部仪器", "EXTERNAL_ARTIFACT"));
    var fixture = fixture(original, List.of(slot(3, "说明仪器校验步骤", "PROVIDED_TEXT"),
        slot(2, "说明图形编辑步骤", "PROVIDED_TEXT")));
    var plan = fixture.plan(List.of());
    assertThat(plan.items()).extracting(KnowledgeAssessmentPlanningService.PlanItem::task)
        .containsExactly("解释视图", "说明图形编辑步骤", "说明仪器校验步骤");
    assertThat(plan.items()).extracting(KnowledgeAssessmentPlanningService.PlanItem::sequence).containsExactly(1, 2, 3);
    verify(fixture.ai, times(1)).analyseJsonFast(anyString(), anyString(), eq("ASSESSMENT_PLAN_MATERIAL_RECOVERY"));
  }

  @Test void honorsExplicitTypeDifficultyAndPointsAndAllowsRealProvidedImages() throws Exception {
    var bad = slot(1, "操作给定DWG文件", "EXTERNAL_ARTIFACT");
    var replacement = slot(1, "在附图中分析视图关系", "PROVIDED_IMAGE");
    var fixture = fixture(List.of(bad), List.of(replacement));
    replacement.put("documentId", fixture.document); replacement.put("page", 2); replacement.put("needsImage", true);
    when(fixture.visuals.pageCount(fixture.document)).thenReturn(2);
    fixture.replacement(List.of(replacement));
    var plan = fixture.plan(List.of(Map.of("type", "PRACTICAL_TASK", "difficulty", "MEDIUM", "points", 8)));
    var repaired = plan.items().getFirst();
    assertThat(repaired.type()).isEqualTo("PRACTICAL_TASK");
    assertThat(repaired.difficulty()).isEqualTo("MEDIUM");
    assertThat(repaired.points()).isEqualTo(8);
    assertThat(repaired.documentId()).isEqualTo(fixture.document);
    assertThat(repaired.page()).isEqualTo(2);
    assertThat(repaired.needsImage()).isTrue();
  }

  @Test void doesNotRecoverAlreadyAnswerablePlansOrTheoreticalDwgFormatQuestions() throws Exception {
    var fixture = fixture(List.of(slot(1, "解释DWG格式的用途", "PROVIDED_TEXT")), List.of());
    assertThat(fixture.plan(List.of()).items()).hasSize(1);
    verify(fixture.ai, never()).analyseJsonFast(anyString(), anyString(), eq("ASSESSMENT_PLAN_MATERIAL_RECOVERY"));
  }

  @Test void relabelingMaterialWithoutRemovingFileDependencyIsStillRejectedOnce() throws Exception {
    var replacement = slot(1, "解释视图关系", "PROVIDED_TEXT");
    replacement.put("answerability", "考生需要打开DWG文件核对答案");
    var fixture = fixture(List.of(slot(1, "编辑给定DWG文件", "EXTERNAL_ARTIFACT")), List.of(replacement));
    assertThat(assertThrows(IllegalStateException.class, () -> fixture.plan(List.of())).getMessage())
        .contains("第 1 题位", "仍依赖外部源文件");
    verify(fixture.ai, times(1)).analyseJsonFast(anyString(), anyString(), eq("ASSESSMENT_PLAN_MATERIAL_RECOVERY"));
  }

  @Test void replacementMustNotOverwriteValidSlotsOrReduceQuestionCount() throws Exception {
    for (var replacements : List.of(List.<Map<String, Object>>of(), List.of(slot(1, "替换错误题号", "PROVIDED_TEXT")))) {
      var fixture = fixture(List.of(slot(1, "有效任务", "PROVIDED_TEXT"),
          slot(2, "编辑给定DWG文件", "EXTERNAL_ARTIFACT")), replacements);
      assertThrows(IllegalStateException.class, () -> fixture.plan(List.of()));
      verify(fixture.ai, times(1)).analyseJsonFast(anyString(), anyString(), eq("ASSESSMENT_PLAN_MATERIAL_RECOVERY"));
    }
  }

  @Test void revalidatesEvidenceImagesAndDuplicatesInTheMergedPlan() throws Exception {
    var unknownEvidence = slot(2, "文字任务", "PROVIDED_TEXT"); unknownEvidence.put("documentId", UUID.randomUUID());
    var missingImage = slot(2, "分析附图", "PROVIDED_IMAGE");
    var duplicate = slot(2, "相同任务", "PROVIDED_TEXT");
    for (var replacement : List.of(unknownEvidence, missingImage, duplicate)) {
      var valid = slot(1, "相同任务", "PROVIDED_TEXT"); valid.put("competency", "能力2");
      var fixture = fixture(List.of(valid, slot(2, "编辑给定DWG文件", "EXTERNAL_ARTIFACT")), List.of(replacement));
      assertThrows(IllegalStateException.class, () -> fixture.plan(List.of()));
      verify(fixture.ai, times(1)).analyseJsonFast(anyString(), anyString(), eq("ASSESSMENT_PLAN_MATERIAL_RECOVERY"));
    }
  }

  private static Map<String, Object> slot(int sequence, String task, String material) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("sequence", sequence); item.put("competency", "能力" + sequence); item.put("task", task);
    item.put("type", "SHORT_ANSWER"); item.put("difficulty", "HARD"); item.put("points", 5);
    item.put("requiredMaterial", material); item.put("knowledgeUse", "GENERAL");
    item.put("answerability", "依据题干中给定信息作答");
    return item;
  }

  private static Fixture fixture(List<Map<String, Object>> original, List<Map<String, Object>> replacements) throws Exception {
    UUID document = UUID.randomUUID();
    var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:material-recovery-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("create table document_pages(document_id uuid, page_no int, markdown clob, active boolean)");
    jdbc.execute("create table document_chunks(document_id uuid, chunk_index int, content clob)");
    jdbc.update("insert into document_pages values(?,1,'CAD视图与纸面作答背景',true)", document);
    var ai = mock(DeepSeekService.class);
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_PLAN")))
        .thenReturn(new ObjectMapper().writeValueAsString(Map.of("summary", "CAD能力", "items", original)));
    var visuals = mock(KnowledgeVisualService.class);
    var service = new KnowledgeAssessmentPlanningService(jdbc, new ObjectMapper(), ai, visuals, mock(DeepSeekWebSearchService.class));
    var fixture = new Fixture(service, ai, visuals, document, original.size());
    fixture.replacement(replacements);
    return fixture;
  }

  private record Fixture(KnowledgeAssessmentPlanningService service, DeepSeekService ai,
      KnowledgeVisualService visuals, UUID document, int count) {
    void replacement(List<Map<String, Object>> replacements) throws Exception {
      when(ai.analyseJsonFast(anyString(), anyString(), eq("ASSESSMENT_PLAN_MATERIAL_RECOVERY")))
          .thenReturn(new ObjectMapper().writeValueAsString(Map.of("items", replacements)));
    }
    KnowledgeAssessmentPlanningService.AssessmentPlan plan(List<Map<String, Object>> fixed) {
      var project = new ExamProjectService.ProjectView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
          "CAD", "KNOWLEDGE_BASE", "DRAFT", "", 1, "{}", "[]", false, 1, Instant.now(), Instant.now(), List.of());
      var snapshot = new ExamProjectEvidenceService.PreparationView(UUID.randomUUID(), project.id(), 1, "READY", List.of(),
          List.of(Map.of("sourceId", document, "sourceType", "DOCUMENT", "name", "CAD.pdf", "sourceRole", "OTHER")),
          0, Map.of(), "hash", Instant.now());
      return service.propose(project, snapshot, count, fixed);
    }
  }
}
