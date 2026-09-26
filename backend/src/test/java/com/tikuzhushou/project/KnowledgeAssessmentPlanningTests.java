package com.tikuzhushou.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.ai.DeepSeekService;
import com.tikuzhushou.assistant.DeepSeekWebSearchService;
import java.time.Instant;
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

class KnowledgeAssessmentPlanningTests {
  @Test
  void usesOriginalImageWhenDocumentHasNoExtractableText() {
    UUID document = UUID.randomUUID();
    JdbcTemplate jdbc = database(document);
    jdbc.update("delete from document_pages where document_id=?", document);
    DeepSeekService ai = mock(DeepSeekService.class);
    KnowledgeVisualService visuals = mock(KnowledgeVisualService.class);
    when(visuals.pageCount(document)).thenReturn(1);
    when(visuals.page(document, 1, 0, 0, 100, 100)).thenReturn(new byte[] { 1 });
    when(ai.analyseAssessmentImagesJsonFast(anyString(), anyString(), anyList(), anyInt(), eq("ASSESSMENT_VISUAL_SURVEY")))
        .thenReturn("{\"summary\":\"图中有两个阀门与压力表\"}");
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_PLAN")))
        .thenReturn("{\"summary\":\"识图应用\",\"items\":[{\"competency\":\"判断阀门状态\",\"task\":\"看图选择隔离动作\",\"type\":\"SINGLE_CHOICE\",\"difficulty\":\"MEDIUM\",\"points\":2,\"documentId\":\"" + document + "\",\"page\":1,\"needsImage\":true}]}");
    var service = new KnowledgeAssessmentPlanningService(jdbc, new ObjectMapper(), ai, visuals, mock(DeepSeekWebSearchService.class));

    var plan = service.propose(project(), snapshot(document), 1, List.of());

    assertThat(plan.items().getFirst().needsImage()).isTrue();
    assertThat(plan.items().getFirst().visualSummary()).contains("压力表");
    assertThat(plan.pipelineVersion()).isEqualTo(KnowledgeAssessmentPlanningService.OPEN_ASSESSMENT_VERSION);
    assertThat(plan.corpusContext()).contains("图中有两个阀门与压力表");
    verify(ai, times(1)).analyseAssessmentImagesJsonFast(anyString(), anyString(), anyList(), anyInt(), eq("ASSESSMENT_VISUAL_SURVEY"));
    ArgumentCaptor<Integer> outputBudget = ArgumentCaptor.forClass(Integer.class);
    verify(ai).analyseJson(anyString(), anyString(), outputBudget.capture(), eq("high"), eq("ASSESSMENT_PLAN"));
    assertThat(outputBudget.getValue()).isGreaterThanOrEqualTo(12_000);
  }

  @Test
  void optionalVisualSurveyFailureDoesNotAbortAssessmentPlan() {
    UUID document = UUID.randomUUID();
    JdbcTemplate jdbc = database(document);
    jdbc.update("delete from document_pages where document_id=?", document);
    DeepSeekService ai = mock(DeepSeekService.class);
    KnowledgeVisualService visuals = mock(KnowledgeVisualService.class);
    when(visuals.pageCount(document)).thenReturn(1);
    when(visuals.page(document, 1, 0, 0, 100, 100)).thenReturn(new byte[] { 1 });
    when(ai.analyseAssessmentImagesJsonFast(anyString(), anyString(), anyList(), anyInt(), eq("ASSESSMENT_VISUAL_SURVEY")))
        .thenThrow(new IllegalStateException("视觉模型暂不可用"));
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_PLAN")))
        .thenReturn("{\"summary\":\"识图应用\",\"items\":[{\"competency\":\"识别图形\",\"task\":\"看图作答\",\"type\":\"SINGLE_CHOICE\",\"difficulty\":\"MEDIUM\",\"documentId\":\"" + document + "\",\"page\":1,\"needsImage\":true}]}");
    var service = new KnowledgeAssessmentPlanningService(jdbc, new ObjectMapper(), ai, visuals, mock(DeepSeekWebSearchService.class));

    var plan = service.propose(project(), snapshot(document), 1, List.of());

    assertThat(plan.items()).hasSize(1);
    assertThat(plan.items().getFirst().needsImage()).isTrue();
    assertThat(plan.items().getFirst().visualSummary()).isBlank();
  }

  @Test
  void plansFromTheWholeCorpusAndLetsModelChooseTypes() {
    UUID document = UUID.randomUUID();
    JdbcTemplate jdbc = database(document);
    DeepSeekService ai = mock(DeepSeekService.class);
    KnowledgeVisualService visuals = mock(KnowledgeVisualService.class);
    when(visuals.pageCount(document)).thenReturn(2);
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_PLAN")))
        .thenReturn("{\"summary\":\"跨页应用能力\",\"items\":[{\"sequence\":1,\"competency\":\"识别设备风险\",\"task\":\"结合参数判断风险\",\"type\":\"CASE_ANALYSIS\",\"difficulty\":\"HARD\",\"points\":8,\"documentId\":\"" + document + "\",\"page\":2,\"searchQuery\":\"参数 风险\",\"needsImage\":false}]}");
    var service = new KnowledgeAssessmentPlanningService(jdbc, new ObjectMapper(), ai, visuals, mock(DeepSeekWebSearchService.class));

    var plan = service.propose(project(), snapshot(document), 1, List.of());

    assertThat(plan.items()).hasSize(1);
    assertThat(plan.items().getFirst().type()).isEqualTo("CASE_ANALYSIS");
    assertThat(plan.items().getFirst().points()).isEqualTo(8);
    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(ai).analyseJson(anyString(), prompt.capture(), anyInt(), anyString(), eq("ASSESSMENT_PLAN"));
    assertThat(prompt.getValue()).contains("第一页设备配置", "第二页风险参数");
  }

  @Test
  void surveysReadableOriginalPagesBeforePlanning() {
    UUID document = UUID.randomUUID();
    JdbcTemplate jdbc = database(document);
    DeepSeekService ai = mock(DeepSeekService.class);
    KnowledgeVisualService visuals = mock(KnowledgeVisualService.class);
    when(visuals.pageCount(document)).thenReturn(2);
    when(visuals.page(document, 1, 0, 0, 100, 100)).thenReturn(new byte[] { 1 });
    when(visuals.page(document, 2, 0, 0, 100, 100)).thenReturn(new byte[] { 2 });
    when(ai.analyseAssessmentImagesJsonFast(anyString(), anyString(), anyList(), anyInt(), eq("ASSESSMENT_VISUAL_SURVEY")))
        .thenReturn("{\"summary\":\"图面标注为Ra1.6，需核对文字转写\"}");
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_PLAN")))
        .thenReturn("{\"summary\":\"识图应用\",\"disciplineBrief\":\"图面判断\",\"items\":[{\"competency\":\"判断图面标注\",\"task\":\"检查粗糙度标注\",\"type\":\"SHORT_ANSWER\",\"difficulty\":\"MEDIUM\",\"documentId\":\"" + document + "\",\"page\":2,\"needsImage\":true,\"requiredMaterial\":\"PROVIDED_IMAGE\",\"knowledgeUse\":\"DERIVED\",\"answerability\":\"考生观察原图回答\"}]}");
    var service = new KnowledgeAssessmentPlanningService(jdbc, new ObjectMapper(), ai, visuals, mock(DeepSeekWebSearchService.class));

    var plan = service.propose(project(), snapshot(document), 1, List.of());

    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(ai).analyseJson(anyString(), prompt.capture(), anyInt(), anyString(), eq("ASSESSMENT_PLAN"));
    assertThat(prompt.getValue()).contains("Ra1.6");
    assertThat(plan.corpusContext()).contains("第一页设备配置", "Ra1.6");
    assertThat(plan.items().getFirst().answerability()).contains("原图");
    verify(ai, times(2)).analyseAssessmentImagesJsonFast(anyString(), anyString(), anyList(), anyInt(), eq("ASSESSMENT_VISUAL_SURVEY"));
  }

  @Test
  void rejectsPlanThatRequiresMissingDwg() {
    UUID document = UUID.randomUUID();
    JdbcTemplate jdbc = database(document);
    DeepSeekService ai = mock(DeepSeekService.class);
    KnowledgeVisualService visuals = mock(KnowledgeVisualService.class);
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_PLAN")))
        .thenReturn("{\"summary\":\"实操\",\"items\":[{\"competency\":\"CAD修复\",\"task\":\"修复给定DWG文件并提交\",\"type\":\"PRACTICAL_TASK\",\"difficulty\":\"HARD\",\"documentId\":\"" + document + "\",\"requiredMaterial\":\"EXTERNAL_ARTIFACT\"}]}");
    var service = new KnowledgeAssessmentPlanningService(jdbc, new ObjectMapper(), ai, visuals, mock(DeepSeekWebSearchService.class));

    assertThat(assertThrows(IllegalStateException.class,
        () -> service.propose(project(), snapshot(document), 1, List.of())).getMessage()).contains("未提供的 DWG");
  }

  @Test
  void readsPreviouslyFrozenPlansWithoutTheNewVersionFields() throws Exception {
    String oldJson = "{\"summary\":\"旧题位\",\"items\":[{\"sequence\":1,\"competency\":\"图层\","
        + "\"task\":\"检查图层\",\"type\":\"SHORT_ANSWER\",\"difficulty\":\"MEDIUM\","
        + "\"points\":4,\"documentId\":\"" + UUID.randomUUID() + "\",\"page\":1,\"searchQuery\":\"\","
        + "\"needsImage\":false,\"visualSummary\":\"\"}],\"webEvidence\":null}";

    KnowledgeAssessmentPlanningService.AssessmentPlan old = new ObjectMapper().readValue(oldJson,
        KnowledgeAssessmentPlanningService.AssessmentPlan.class);

    assertThat(old.pipelineVersion()).isNull();
    assertThat(old.items()).hasSize(1);
    assertThat(old.items().getFirst().webEvidence()).isNull();
  }

  @Test
  void rejectsUnselectedEvidenceInsteadOfInventingSource() {
    UUID document = UUID.randomUUID();
    JdbcTemplate jdbc = database(document);
    DeepSeekService ai = mock(DeepSeekService.class);
    KnowledgeVisualService visuals = mock(KnowledgeVisualService.class);
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_PLAN")))
        .thenReturn("{\"summary\":\"bad\",\"items\":[{\"competency\":\"能力\",\"task\":\"任务\",\"type\":\"SINGLE_CHOICE\",\"difficulty\":\"MEDIUM\",\"documentId\":\"" + UUID.randomUUID() + "\"}]}");
    var service = new KnowledgeAssessmentPlanningService(jdbc, new ObjectMapper(), ai, visuals, mock(DeepSeekWebSearchService.class));

    assertThrows(IllegalStateException.class, () -> service.propose(project(), snapshot(document), 1, List.of()));
  }

  @Test
  void leavesResearchToAuthoringInsteadOfBlockingTheWholePlanOnSearch() {
    UUID document = UUID.randomUUID();
    JdbcTemplate jdbc = database(document);
    DeepSeekService ai = mock(DeepSeekService.class);
    KnowledgeVisualService visuals = mock(KnowledgeVisualService.class);
    DeepSeekWebSearchService web = mock(DeepSeekWebSearchService.class);
    when(ai.analyseJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_WEB_QUERY")))
        .thenReturn("{\"query\":\"设备风险 参数 判定\"}");
    when(web.searchForAssessment("设备风险 参数 判定")).thenReturn(new DeepSeekWebSearchService.SearchAnswer(
        "适用条件 A 下应采用 B。https://example.org/standard", List.of(
            new DeepSeekWebSearchService.SearchSource("公开技术资料", "https://example.org/standard"))));
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_PLAN")))
        .thenReturn("{\"summary\":\"应用能力\",\"items\":[{\"competency\":\"设备风险\",\"task\":\"判断处理方案\",\"type\":\"SINGLE_CHOICE\",\"difficulty\":\"MEDIUM\",\"documentId\":\"" + document + "\",\"knowledgeUse\":\"WEB\",\"searchQuery\":\"设备风险公开判定\"}]}");
    var base = project();
    var enabled = new ExamProjectService.ProjectView(base.id(), base.ownerId(), base.knowledgeBaseId(),
        base.name(), base.mode(), base.status(), base.requirementText(), base.variantCount(),
        base.difficultyProfileJson(), base.scoringStructureJson(), base.authorizationConfirmed(), true,
        base.sourceCount(), base.createdAt(), base.updatedAt(), base.sources());
    var service = new KnowledgeAssessmentPlanningService(jdbc, new ObjectMapper(), ai, visuals, web);

    var plan = service.propose(enabled, snapshot(document), 1, List.of());

    assertThat(plan.items().getFirst().webEvidence()).isNull();
    assertThat(plan.items().getFirst().searchQuery()).isEqualTo("设备风险公开判定");
    assertThat(plan.webEvidence()).isNull();
    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(ai).analyseJson(anyString(), prompt.capture(), anyInt(), anyString(), eq("ASSESSMENT_PLAN"));
    assertThat(prompt.getValue()).contains("可按题定向联网核实");
    verifyNoInteractions(web);
  }

  @Test
  void generalKnowledgeAndTransferTasksDoNotRequireADocumentAnchor() {
    UUID document = UUID.randomUUID();
    JdbcTemplate jdbc = database(document);
    DeepSeekService ai = mock(DeepSeekService.class);
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_PLAN")))
        .thenReturn("{\"summary\":\"比例应用\",\"items\":[{\"competency\":\"比例迁移\",\"task\":\"根据新情境计算缩放比例\",\"type\":\"CALCULATION\",\"difficulty\":\"MEDIUM\",\"documentId\":null,\"knowledgeUse\":\"GENERAL\"}]}");
    var service = new KnowledgeAssessmentPlanningService(jdbc, new ObjectMapper(), ai,
        mock(KnowledgeVisualService.class), mock(DeepSeekWebSearchService.class));

    var plan = service.propose(project(), snapshot(document), 1, List.of());

    assertThat(plan.items().getFirst().documentId()).isNull();
    assertThat(plan.items().getFirst().page()).isZero();
    assertThat(plan.pipelineVersion()).isEqualTo("OPEN_ASSESSMENT_V2");
  }

  @Test
  void coversTheTailOfLongCorporaInsteadOfSilentlyTruncatingIt() {
    UUID document = UUID.randomUUID();
    JdbcTemplate jdbc = database(document);
    jdbc.update("update document_pages set markdown=? where document_id=? and page_no=1", "前部概念".repeat(4000), document);
    jdbc.update("update document_pages set markdown=? where document_id=? and page_no=2", "末尾重要实验方法", document);
    DeepSeekService ai = mock(DeepSeekService.class);
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_INDEX")))
        .thenAnswer(invocation -> invocation.<String>getArgument(1).contains("末尾重要实验方法")
            ? "{\"summary\":\"末尾重要实验方法\"}" : "{\"summary\":\"前部概念\"}");
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_PLAN")))
        .thenReturn("{\"summary\":\"实验设计\",\"items\":[{\"competency\":\"实验\",\"task\":\"设计对照实验\",\"type\":\"SHORT_ANSWER\",\"difficulty\":\"MEDIUM\"}]}");
    var service = new KnowledgeAssessmentPlanningService(jdbc, new ObjectMapper(), ai,
        mock(KnowledgeVisualService.class), mock(DeepSeekWebSearchService.class));
    assertThat(service.propose(project(), snapshot(document), 1, List.of()).corpusContext())
        .contains("前部概念", "末尾重要实验方法");
  }

  @Test
  void automaticTypeSelectionStillHonorsExplicitDifficultySlots() {
    UUID document = UUID.randomUUID();
    DeepSeekService ai = mock(DeepSeekService.class);
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_PLAN")))
        .thenReturn("{\"summary\":\"比例应用\",\"items\":[{\"competency\":\"比例迁移\",\"task\":\"计算缩放比例\",\"type\":\"CALCULATION\",\"difficulty\":\"EASY\",\"points\":4}]}");
    var service = new KnowledgeAssessmentPlanningService(database(document), new ObjectMapper(), ai,
        mock(KnowledgeVisualService.class), mock(DeepSeekWebSearchService.class));
    var item = service.propose(project(), snapshot(document), 1, List.of(Map.of("difficulty", "HARD"))).items().getFirst();
    assertThat(item.difficulty()).isEqualTo("HARD");
    assertThat(item.type()).isEqualTo("CALCULATION");
    assertThat(item.points()).isEqualTo(4);
  }

  private static JdbcTemplate database(UUID document) {
    DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:assessment-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate jdbc = new JdbcTemplate(source);
    jdbc.execute("create table document_pages(document_id uuid, page_no int, markdown clob, active boolean)");
    jdbc.execute("create table document_chunks(document_id uuid, chunk_index int, content clob)");
    jdbc.update("insert into document_pages values(?,?,?,true)", document, 1, "第一页设备配置");
    jdbc.update("insert into document_pages values(?,?,?,true)", document, 2, "第二页风险参数");
    return jdbc;
  }

  private static ExamProjectService.ProjectView project() {
    return new ExamProjectService.ProjectView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        "测试", "KNOWLEDGE_BASE", "DRAFT", "", 1, "{}", "[]", true, 1, Instant.now(), Instant.now(), List.of());
  }

  private static ExamProjectEvidenceService.PreparationView snapshot(UUID document) {
    return new ExamProjectEvidenceService.PreparationView(UUID.randomUUID(), UUID.randomUUID(), 1, "READY",
        List.of(), List.of(Map.of("sourceId", document, "sourceType", "DOCUMENT", "name", "测试资料", "sourceRole", "OTHER")),
        0, Map.of(), "hash", Instant.now());
  }
}
