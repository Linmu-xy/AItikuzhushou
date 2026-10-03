package com.tikuzhushou;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.tikuzhushou.ai.DeepSeekService;
import com.tikuzhushou.assistant.DeepSeekWebSearchService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"APP_BOOTSTRAP_ADMIN_PASSWORD=test-only-password","spring.datasource.url=jdbc:h2:mem:tiku-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","app.queue.provider=local"})
@AutoConfigureMockMvc
class TikuApplicationTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.tikuzhushou.document.DocumentParsingService parsing;
  @MockBean DeepSeekService deepSeek;
  @MockBean DeepSeekWebSearchService webSearch;
  @Autowired com.tikuzhushou.quota.ModelQuotaService modelQuota;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  @Test void modelQuotaDoesNotHoldItsRowLockForTheEntirePlanningTransaction() throws Exception {
    var reserved = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var outer = executor.submit(() -> {
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        context.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken("admin", ""));
        try {
          new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            modelQuota.reserve(1);
            reserved.countDown();
            try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
          });
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
      });
      try {
        assertTrue(reserved.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var concurrent = executor.submit(() -> {
          org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
              new org.springframework.security.authentication.TestingAuthenticationToken("admin", ""));
          try { return modelQuota.reserve(1); }
          finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        });
        assertNotNull(concurrent.get(5, java.util.concurrent.TimeUnit.SECONDS));
      } finally { release.countDown(); }
      outer.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  private String basic(String username, String password) {
    return "Basic " + java.util.Base64.getEncoder().encodeToString(
        (username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test void healthIsPublic() throws Exception { mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP")); }

  @Test void conversationFilesStayScopedToConversationAndCanBeRemoved() throws Exception {
    String authorization = basic("admin", "test-only-password");
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var base = mvc.perform(post("/api/knowledge-bases").header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"会话附件测试库\",\"description\":\"隔离验证\"}"))
        .andExpect(status().isOk()).andReturn();
    String baseId = mapper.readTree(base.getResponse().getContentAsString()).path("id").asText();
    var conversation = mvc.perform(post("/api/assistant/conversations").header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"仅对话文件\",\"mode\":\"KNOWLEDGE_BASE\",\"knowledgeBaseId\":\"" + baseId + "\"}"))
        .andExpect(status().isOk()).andReturn();
    String conversationId = mapper.readTree(conversation.getResponse().getContentAsString()).path("id").asText();
    var file = new MockMultipartFile("file", "notes.txt", "text/plain",
        "设备启动前必须检查防护装置，并记录交接班异常情况。".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var uploaded = mvc.perform(multipart("/api/assistant/conversations/{id}/attachments", conversationId)
        .file(file).header("Authorization", authorization))
        .andExpect(status().isOk()).andExpect(jsonPath("$.originalName").value("notes.txt")).andReturn();
    String attachmentId = mapper.readTree(uploaded.getResponse().getContentAsString()).path("id").asText();
    mvc.perform(get("/api/assistant/conversations/{id}/attachments", conversationId).header("Authorization", authorization))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    when(deepSeek.analyseJson(anyString(), anyString(), eq(4096)))
        .thenReturn("{\"blocks\":[{\"evidenceType\":\"DIRECT_SOURCE\",\"segments\":[{\"text\":\"启动前应检查防护装置。\",\"sources\":[\"A1\"]}]}]}");
    mvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId).header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"prompt\":\"附件中启动前应检查什么？\",\"mode\":\"KNOWLEDGE_BASE\",\"reasoningEffort\":\"FAST\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.message.statusCode").value("OK"))
        .andExpect(jsonPath("$.message.citations[0].sourceType").value("CHAT_ATTACHMENT"));
    mvc.perform(delete("/api/assistant/conversations/{id}/attachments/{fileId}", conversationId, attachmentId)
        .header("Authorization", authorization)).andExpect(status().isNoContent());
    mvc.perform(get("/api/assistant/conversations/{id}/attachments", conversationId).header("Authorization", authorization))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/knowledge-bases/{id}/documents", baseId).header("Authorization", authorization))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
  }

  @Test void optInWebSearchIsSeparateFromKnowledgeBaseEvidence() throws Exception {
    String authorization = basic("admin", "test-only-password");
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var base = mvc.perform(post("/api/knowledge-bases").header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"联网补充测试库\",\"description\":\"不混入出题证据\"}"))
        .andExpect(status().isOk()).andReturn();
    String baseId = mapper.readTree(base.getResponse().getContentAsString()).path("id").asText();
    var conversation = mvc.perform(post("/api/assistant/conversations").header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"联网测试\",\"mode\":\"KNOWLEDGE_BASE\",\"knowledgeBaseId\":\"" + baseId + "\"}"))
        .andExpect(status().isOk()).andReturn();
    String conversationId = mapper.readTree(conversation.getResponse().getContentAsString()).path("id").asText();
    when(webSearch.search("DeepSeek 最近有什么更新？")).thenReturn(new DeepSeekWebSearchService.SearchAnswer(
        "官方更新日志列出了近期更新。", java.util.List.of(new DeepSeekWebSearchService.SearchSource(
            "DeepSeek 更新日志", "https://api-docs.deepseek.com/updates/"))));
    mvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId).header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"prompt\":\"DeepSeek 最近有什么更新？\",\"mode\":\"KNOWLEDGE_BASE\",\"webSearch\":true}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.message.statusCode").value("OK"))
        .andExpect(jsonPath("$.message.answerBlocks[0].evidenceType").value("NOT_COVERED"))
        .andExpect(jsonPath("$.message.answerBlocks[1].evidenceType").value("WEB_SUPPLEMENT"))
        .andExpect(jsonPath("$.message.citations[0].sourceType").value("WEB"))
        .andExpect(jsonPath("$.message.citations[0].chunkId").value("https://api-docs.deepseek.com/updates/"));
    mvc.perform(get("/api/assistant/conversations/{id}/messages", conversationId).header("Authorization", authorization))
        .andExpect(status().isOk()).andExpect(jsonPath("$[1].answerBlocks[1].evidenceType").value("WEB_SUPPLEMENT"));
    when(webSearch.search("再次联网检索")).thenThrow(new IllegalStateException("搜索服务暂不可用"));
    mvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId).header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"prompt\":\"再次联网检索\",\"mode\":\"KNOWLEDGE_BASE\",\"webSearch\":true}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.message.statusCode").value("WEB_SEARCH_UNAVAILABLE"))
        .andExpect(jsonPath("$.message.answerBlocks[0].evidenceType").value("NOT_COVERED"))
        .andExpect(jsonPath("$.message.answerBlocks[1].evidenceType").value("WEB_UNAVAILABLE"));
  }

  @Test void careerProjectHasIndependentMode() throws Exception {
    String authorization = basic("admin", "test-only-password");
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var base = mvc.perform(post("/api/knowledge-bases").header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"职业项目测试库\",\"description\":\"模式验证\"}"))
        .andExpect(status().isOk()).andReturn();
    String baseId = mapper.readTree(base.getResponse().getContentAsString()).path("id").asText();
    mvc.perform(post("/api/exam-projects").header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"安全操作职业命题\",\"mode\":\"CAREER\",\"knowledgeBaseId\":\"" + baseId
            + "\",\"authorizationConfirmed\":true,\"variantCount\":1,\"requirementText\":\"选定考点：\\n- 防护装置检查\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.mode").value("CAREER"));
  }

  @Test void projectQuestionReviewAndExportReadinessRequireAuthentication() throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    mvc.perform(get("/api/exam-projects/{projectId}/variant-generation-runs/{runId}/export-readiness", projectId, runId))
        .andExpect(status().isUnauthorized());
    mvc.perform(patch("/api/exam-projects/{projectId}/variant-generation-runs/{runId}/items/{itemId}/review", projectId, runId, itemId)
        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/exam-projects/{projectId}/variant-generation-runs/{runId}/exports", projectId, runId))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/exam-projects/{projectId}/variant-generation-runs/{runId}/exports", projectId, runId)
        .contentType(MediaType.APPLICATION_JSON).content("{\"outputTypes\":[\"PAPER_XLSX\"]}"))
        .andExpect(status().isUnauthorized());
  }

  @Test void contextAssetsRequireApprovalAndNeverExposeRawContent() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var knowledgeBase = mvc.perform(post("/api/knowledge-bases")
        .header("Authorization", basic("admin", "test-only-password"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"深度命题资料测试库\",\"description\":\"用于验证资料审核流程\"}"))
      .andExpect(status().isOk()).andReturn();
    String knowledgeBaseId = mapper.readTree(knowledgeBase.getResponse().getContentAsString()).path("id").asText();

    String content = "设备交接班出现防护装置确认记录缺失时，班组通常先复核交接记录、现场状态与防护装置，确认完成后再进入启动准备。";
    var asset = mvc.perform(post("/api/knowledge-bases/{id}/context-assets", knowledgeBaseId)
        .header("Authorization", basic("admin", "test-only-password"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"type":"CONTEXT","title":"设备交接异常摘要","sourceOrganization":"企业安全部",
             "licenseStatus":"INTERNAL","profession":"测试职业","level":"三级/高级工",
             "trustLevel":"ENTERPRISE","content":"%s"}
            """.formatted(content)))
      .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
      .andExpect(jsonPath("$.content").doesNotExist()).andReturn();
    String assetId = mapper.readTree(asset.getResponse().getContentAsString()).path("id").asText();

    mvc.perform(get("/api/context-assets").param("knowledgeBaseId", knowledgeBaseId)
        .header("Authorization", basic("admin", "test-only-password")))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("PENDING"))
      .andExpect(jsonPath("$[0].content").doesNotExist());
    mvc.perform(post("/api/context-assets/{id}/approve", assetId)
        .header("Authorization", basic("admin", "test-only-password"))
        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test void occupationalStandardExtractionBuildsASeparateOutlineForEachLevel() {
    var parser = new com.tikuzhushou.standard.OccupationalStandardParserService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true);
    String source = "职业名称：测试职业\n三级/高级工\n职业功能 加工操作\n" + String.join("\n", java.util.List.of(
        "原料处理1 技能要求 能够完成原料处理1 相关知识 安全规范1",
        "原料处理2 技能要求 能够完成原料处理2 相关知识 安全规范2",
        "原料处理3 技能要求 能够完成原料处理3 相关知识 安全规范3",
        "原料处理4 技能要求 能够完成原料处理4 相关知识 安全规范4",
        "原料处理5 技能要求 能够完成原料处理5 相关知识 安全规范5"))
        + "\n职业标准内容用于验证按等级形成独立考评细目表，所有技能要求和相关知识均来自本段原文。".repeat(4);
    String details = String.join(",", java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(index ->
        "{\"code\":\"00" + index + "\",\"name\":\"原料处理" + index + "\",\"importance\":\"重要\","
            + "\"skillRequirement\":\"能够完成原料处理" + index + "\",\"relatedKnowledgeRequirement\":\"安全规范" + index
            + "\",\"sourceEvidence\":\"能够完成原料处理" + index + "\"}").toList());
    when(deepSeek.analyseJson(anyString(), anyString(), eq(6144))).thenReturn("""
        {"profession":"测试职业","occupationCode":"4-99-99-99",
        "outline":[{"code":"A","name":"加工操作","weight":5,"relatedKnowledgeRequirement":"安全规范","children":[],"assessmentPoints":[%s]}]}
        """.formatted(details));
    var result = parser.parse(source);
    assertEquals(java.util.List.of("三级/高级工"), result.levels());
    assertEquals(1, result.levelDetails().size());
    assertEquals(5, result.levelDetails().getFirst().outline().getFirst().assessmentPoints().size());
    assertEquals("001", result.levelDetails().getFirst().outline().getFirst().assessmentPoints().getFirst().code());
  }
  @Test void occupationalLevelDiscoveryUsesModelAndKeepsOnlySourceAnchoredLevels() {
    var parser = new com.tikuzhushou.standard.OccupationalStandardParserService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true);
    String source = ("职业名称：测试职业\\n职业编码：4-99-99-99\\n三级/高级工\\n四级/中级工\\n"
        + "本职业标准用于验证模型识别职业等级，正文包含职业活动、技能要求与相关知识。 ").repeat(8);
    when(deepSeek.analyseJson(anyString(), anyString(), eq(2048))).thenReturn("""
        {"profession":"测试职业","occupationCode":"4-99-99-99",
         "levels":["三级/高级工","四级/中级工","一级/高级技师"]}
        """);
    var result = parser.discover(source);
    assertEquals("测试职业", result.profession());
    assertEquals(java.util.List.of("四级/中级工", "三级/高级工"), result.levels());
    assertTrue(result.levelDetected());
    verify(deepSeek, times(1)).analyseJson(anyString(), contains("识别职业名称、职业编码和职业等级"), eq(2048));
  }

  @Test void levelExtractionUsesTheWorkRequirementHeadingInsteadOfTheLevelCatalogue() {
    var parser = new com.tikuzhushou.standard.OccupationalStandardParserService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true);
    String rows = String.join("\n", java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(index ->
        "| 康复服务 | 功能促进" + index + " | 能完成二级康复训练" + index + " | 康复训练知识" + index + " |"
    ).toList());
    String source = "# 1 职业概况\n本职业共设五个等级：五级/初级工、四级/中级工、三级/高级工、二级/技师、一级/高级技师。\n"
        + "# 1.8 申报条件\n取得三级/高级工证书后可申报二级/技师。\n"
        + "## 3.4 二级/技师\n| 职业功能 | 工作内容 | 技能要求 | 相关知识要求 |\n" + rows
        + "\n## 3.5 一级/高级技师\n| 职业功能 | 工作内容 | 技能要求 | 相关知识要求 |\n| 技术管理 | 方案制定 | 能制定高级方案 | 管理知识 |";
    String points = String.join(",", java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(index ->
        "{\"code\":\"00" + index + "\",\"name\":\"功能促进" + index + "\",\"importance\":\"重要\","
            + "\"skillRequirement\":\"能完成二级康复训练" + index + "\",\"relatedKnowledgeRequirement\":\"康复训练知识" + index
            + "\",\"sourceEvidence\":\"能完成二级康复训练" + index + "\"}").toList());
    when(deepSeek.analyseJson(anyString(), anyString(), eq(6144))).thenReturn("""
        {"profession":"养老护理员","occupationCode":"4-10-01-05",
        "outline":[{"code":"A","name":"康复服务","weight":null,"relatedKnowledgeRequirement":"康复训练知识","children":[],"assessmentPoints":[%s]}]}
        """.formatted(points));

    var result = parser.parseLevel(source, "二级/技师");

    assertEquals(5, result.assessmentPoints().size());
    org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(deepSeek).analyseJson(anyString(), prompt.capture(), eq(6144));
    assertTrue(prompt.getValue().contains("能完成二级康复训练1"));
    assertFalse(prompt.getValue().contains("能制定高级方案"));
  }

  @Test void professionalReviewerRejectsAQuestionThatCanBeSolvedByCommonSense() {
    var reviewer = new com.tikuzhushou.question.QuestionProfessionalReviewService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), jdbc, true, 75, 5, 35, "ENFORCE");
    Map<String, Object> question = new java.util.LinkedHashMap<>();
    question.put("sequence", 7); question.put("type", "SINGLE_CHOICE"); question.put("difficulty", "MEDIUM");
    question.put("level", "三级/高级工"); question.put("cognitiveTarget", "APPLY");
    question.put("assessmentPoint", "作业前安全确认");
    question.put("stem", "作业人员准备启动设备时，应优先采取哪项措施？");
    question.put("options", "A. 确认防护装置和作业环境 | B. 尽快启动 | C. 关闭照明 | D. 交由顾客决定");
    question.put("answer", "A"); question.put("analysis", "原文要求启动前确认防护装置、设备状态与作业环境。");
    question.put("scoringRubric", "答对得 2 分；答错 0 分。");
    question.put("sourceExcerpt", "作业前安全检查包括确认防护装置、设备状态和作业环境，检查完成后方可启动设备。");
    question.put("_professionalDesign", Map.of("taskContext", "设备启动前检查", "conditions", java.util.List.of("防护装置待确认"),
        "competencyAction", "完成安全确认"));
    when(deepSeek.analyseJson(anyString(), contains("审查 INPUT_JSON"), eq(4096), eq("low"))).thenReturn("""
        {"reviews":[{"sequence":7,"status":"REWRITE","discriminationScore":48,"outsiderSolvableScore":82,
        "flags":["COMMON_SENSE_SOLVABLE","WEAK_DISTRACTOR"],"feedback":"缺少岗位限制，错误项过于明显。",
        "optionReviews":[
          {"option":"A","status":"PASS","flags":[],"misconceptionType":"正确依据","feedback":""},
          {"option":"B","status":"REWRITE","flags":["IMPLAUSIBLE_DISTRACTOR"],"misconceptionType":"条件遗漏","feedback":"不能只写尽快启动。"},
          {"option":"C","status":"REWRITE","flags":["OPTION_DIMENSION_MISMATCH"],"misconceptionType":"对象错配","feedback":"需保持启动前检查维度。"},
          {"option":"D","status":"REWRITE","flags":["IMPLAUSIBLE_DISTRACTOR"],"misconceptionType":"角色错配","feedback":"应改为作业人员可能出现的误判。"}
        ]}]}
        """);

    var result = reviewer.review(java.util.List.of(question)).getFirst();

    assertFalse(result.passed());
    assertEquals(48, result.discriminationScore());
    assertEquals(82, result.outsiderSolvableScore());
    assertTrue(result.flags().containsAll(java.util.List.of("COMMON_SENSE_SOLVABLE", "WEAK_DISTRACTOR", "OUTSIDER_SOLVABLE")));
    assertEquals(4, result.optionReviews().size());
    assertFalse(result.optionReviews().get(1).passed());
    assertTrue(result.feedback().contains("错误项"));
    verify(deepSeek).analyseJson(anyString(), contains("职业处置"), eq(4096), eq("low"));
  }

  @Test void professionalReviewerDoesNotPassChoiceWithoutFourOptionReviews() {
    var reviewer = new com.tikuzhushou.question.QuestionProfessionalReviewService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), jdbc, true, 75, 5, 35, "ENFORCE");
    Map<String, Object> question = new java.util.LinkedHashMap<>();
    question.put("sequence", 8); question.put("type", "SINGLE_CHOICE"); question.put("difficulty", "MEDIUM");
    question.put("stem", "作业人员在设备启动前发现防护装置的检查记录缺失，应如何处理？");
    question.put("options", "A. 补齐检查确认后启动 | B. 先运行再补查 | C. 只核对产量记录 | D. 由其他岗位决定");
    question.put("answer", "A"); question.put("analysis", "原文要求确认防护装置和作业环境后方可启动。");
    question.put("scoringRubric", "答对得 2 分；答错 0 分。");
    question.put("sourceExcerpt", "作业前安全检查包括确认防护装置、设备状态和作业环境，检查完成后方可启动设备。");
    when(deepSeek.analyseJson(anyString(), contains("审查 INPUT_JSON"), eq(4096), eq("low"))).thenReturn("""
        {"reviews":[{"sequence":8,"status":"PASS","discriminationScore":90,"outsiderSolvableScore":20,
        "flags":[],"feedback":""}]}
        """);

    var result = reviewer.review(java.util.List.of(question)).getFirst();

    assertFalse(result.passed());
    assertTrue(result.flags().contains("OPTION_REVIEW_UNAVAILABLE"));
  }

  @Test void professionalReviewerRetriesOnlyTheReviewAndMarksOutageUnavailable() {
    var reviewer = new com.tikuzhushou.question.QuestionProfessionalReviewService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), jdbc, true, 75, 5, 35, "ENFORCE");
    Map<String, Object> question = new java.util.LinkedHashMap<>();
    question.put("sequence", 10); question.put("type", "SINGLE_CHOICE"); question.put("difficulty", "MEDIUM");
    question.put("stem", "作业人员在设备启动前发现确认记录缺失，应如何处理？");
    question.put("options", "A. 补齐确认后启动 | B. 先启动再补查 | C. 只核对产量 | D. 交由其他岗位决定");
    question.put("answer", "A"); question.put("analysis", "应先完成启动前确认。");
    question.put("scoringRubric", "答对得 2 分；答错 0 分。");
    question.put("sourceExcerpt", "设备启动前应确认防护装置和现场状态，确认完成后方可启动设备。");

    when(deepSeek.analyseJson(anyString(), contains("审查 INPUT_JSON"), eq(4096), eq("low")))
        .thenThrow(new IllegalStateException("review timeout"));

    var result = reviewer.review(java.util.List.of(question)).getFirst();

    assertFalse(result.available());
    assertTrue(result.flags().contains("PROFESSIONAL_REVIEW_UNAVAILABLE"));
    verify(deepSeek, times(2)).analyseJson(anyString(), contains("审查 INPUT_JSON"), eq(4096), eq("low"));
  }

    @Test void professionalAuditPersistsOptionLevelFindings() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID adminId = UUID.nameUUIDFromBytes("admin".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update("insert into generation_jobs(id,owner_id,job_type,status,progress,request_json,result_json,retry_count,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?)",
        jobId, adminId, "QUESTION_BANK", "RUNNING", 50, "{}", "[]", 0, now, now);
    var reviewer = new com.tikuzhushou.question.QuestionProfessionalReviewService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), jdbc, true, 75, 5, 35, "ENFORCE");
    var review = new com.tikuzhushou.question.QuestionProfessionalReviewService.Review(1, true, 86, 22,
        java.util.List.of(), "选项区分度合格", java.util.List.of(
            new com.tikuzhushou.question.QuestionProfessionalReviewService.OptionReview("A", true, java.util.List.of(), "正确依据", ""),
            new com.tikuzhushou.question.QuestionProfessionalReviewService.OptionReview("B", true, java.util.List.of(), "条件遗漏", ""),
            new com.tikuzhushou.question.QuestionProfessionalReviewService.OptionReview("C", true, java.util.List.of(), "时序颠倒", ""),
            new com.tikuzhushou.question.QuestionProfessionalReviewService.OptionReview("D", true, java.util.List.of(), "控制点错位", "")));

    reviewer.save(jobId, 1, 1, review, Map.of("optionDesign", Map.of("A", Map.of("conditionFit", "完整"))));
    var audit = reviewer.list(jobId).getFirst();
    var summary = reviewer.summary(jobId);

    assertEquals(22, audit.outsiderSolvableScore());
    assertEquals("ENFORCE", audit.optionReviewMode());
    assertEquals(4, audit.optionReviews().size());
    assertEquals("条件遗漏", audit.optionReviews().get(1).misconceptionType());
    assertEquals(1, summary.reviewedItems());
    assertEquals(1, summary.passedItems());
    mvc.perform(get("/api/quality/jobs/{id}/professional-audits/summary", jobId)
        .header("Authorization", basic("admin", "test-only-password")))
      .andExpect(status().isOk()).andExpect(jsonPath("$.reviewedItems").value(1))
      .andExpect(jsonPath("$.averageOutsiderSolvableScore").value(22.0));
  }

  @Test void shadowModeRecordsWeakDistractorsWithoutBlockingTheQuestion() {
    var reviewer = new com.tikuzhushou.question.QuestionProfessionalReviewService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), jdbc, true, 75, 5, 35, "SHADOW");
    Map<String, Object> question = new java.util.LinkedHashMap<>();
    question.put("sequence", 9); question.put("type", "SINGLE_CHOICE");
    question.put("stem", "设备启动前，作业人员发现防护装置的确认记录不完整，应选择何种处置？");
    question.put("options", "A. 补齐确认后启动 | B. 待运行后补查 | C. 仅核对产量 | D. 交由顾客决定");
    question.put("answer", "A"); question.put("analysis", "原文要求确认防护装置、设备状态与作业环境后方可启动。");
    question.put("scoringRubric", "答对得 2 分；答错 0 分。");
    question.put("sourceExcerpt", "作业前安全检查包括确认防护装置、设备状态和作业环境，检查完成后方可启动设备。");
    when(deepSeek.analyseJson(anyString(), contains("审查 INPUT_JSON"), eq(4096), eq("low"))).thenReturn("""
        {"reviews":[{"sequence":9,"status":"REWRITE","discriminationScore":88,"outsiderSolvableScore":72,
        "flags":["OUTSIDER_SOLVABLE"],"feedback":"错误项过于明显。","optionReviews":[
        {"option":"A","status":"PASS","flags":[],"misconceptionType":"正确依据","feedback":""},
        {"option":"B","status":"REWRITE","flags":["WEAK_DISTRACTOR"],"misconceptionType":"时序颠倒","feedback":"需补足场景条件。"},
        {"option":"C","status":"PASS","flags":[],"misconceptionType":"控制点错位","feedback":""},
        {"option":"D","status":"PASS","flags":[],"misconceptionType":"角色错配","feedback":""}]}]}
        """);

    var result = reviewer.review(java.util.List.of(question)).getFirst();

    assertTrue(result.passed());
    assertTrue(result.flags().contains("OUTSIDER_SOLVABLE"));
  }

  @Test void optionOnlyRewriteCannotAlterAnApprovedStemOrAnswer() {
    var refiner = new com.tikuzhushou.question.QuestionRefinementService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true, 3, true, 1, 6000,
        "high", "high", "high");
    Map<String, Object> draft = new java.util.LinkedHashMap<>();
    draft.put("type", "SINGLE_CHOICE"); draft.put("stem", "原题干"); draft.put("answer", "A");
    draft.put("analysis", "原解析"); draft.put("scoringRubric", "答对得 2 分；答错 0 分。");
    draft.put("options", "A. 原正确项 | B. 原干扰项 | C. 原干扰项 | D. 原干扰项"); draft.put("optionRewriteOnly", true);
    draft.put("_professionalDesign", Map.of("taskContext", "原场景", "conditions", java.util.List.of("原条件"),
        "competencyAction", "原动作"));
    Map<String, Object> generated = Map.of("stem", "被篡改题干", "answer", "B", "analysis", "被篡改解析",
        "scoringRubric", "被篡改评分", "options", "A. 新正确项 | B. 新干扰项 | C. 新干扰项 | D. 新干扰项",
        "_professionalDesign", Map.of("optionDesign", Map.of("A", Map.of("conditionFit", "完整"))));

    @SuppressWarnings("unchecked")
    Map<String, Object> merged = org.springframework.test.util.ReflectionTestUtils.invokeMethod(refiner, "merge", draft, generated);

    assertEquals("原题干", merged.get("stem"));
    assertEquals("A", merged.get("answer"));
    assertEquals("新正确项", String.valueOf(merged.get("options")).split("\\|")[0].replace("A. ", "").trim());
    assertFalse(refiner.deliveryQuestion(merged).containsKey("optionRewriteOnly"));
  }

  @Test void basicReviewCandidateDoesNotRequireProfessionalDesignCard() {
    var refiner = new com.tikuzhushou.question.QuestionRefinementService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true, 3, true, 1, 6000,
        "high", "high", "high");
    Map<String, Object> draft = new java.util.LinkedHashMap<>();
    draft.put("sequence", 1); draft.put("type", "SINGLE_CHOICE"); draft.put("difficulty", "MEDIUM");
    draft.put("level", "三级/高级工"); draft.put("assessmentPoint", "设备启动前安全确认");
    draft.put("sourceRef", "第1页");
    draft.put("sourceExcerpt", "设备启动前应核验防护装置、现场状态和交接记录，确认无异常后方可进入启动准备。");
    draft.put("basicReviewOnly", true);
    when(deepSeek.analyseJson(anyString(), anyString(), eq(4096), anyString())).thenReturn("""
        {"questions":[{"sequence":1,"stem":"设备启动前，操作人员完成交接后发现现场防护装置状态待确认，此时应如何处置？",
        "options":"A. 先核验防护装置和现场状态，再确认交接记录 | B. 先启动设备，运行中再检查防护装置 | C. 只核对交接记录即可进入启动准备 | D. 通知下一班处理后立即离开现场",
        "answer":"A","analysis":"启动准备前需要同时核验防护装置、现场状态和交接记录。发现防护状态待确认时，应先完成现场核验并确认交接信息，确认无异常后再进入启动准备。",
        "scoringRubric":"答对得2分；答错得0分。"}]}
        """);

    var candidate = refiner.generateCandidates(java.util.List.of(draft), "BALANCED").getFirst();

    assertTrue(candidate.hardValid());
    assertTrue(candidate.valid(), "基础审查不应要求仅供职业质量审查使用的设计卡");
    assertFalse(refiner.deliveryQuestion(candidate.question()).containsKey("basicReviewOnly"));
  }

  @Test void expertModeUsesLightweightDesignAndKeepsItOutOfDeliveryQuestion() {
    var refiner = new com.tikuzhushou.question.QuestionRefinementService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true, 3, true, 1, 6000,
        "high", "high", "high");
    Map<String, Object> draft = new java.util.LinkedHashMap<>();
    draft.put("sequence", 1); draft.put("type", "SINGLE_CHOICE"); draft.put("difficulty", "MEDIUM");
    draft.put("level", "三级/高级工"); draft.put("assessmentPoint", "设备启动前安全确认");
    draft.put("sourceRef", "第1页"); draft.put("sourceExcerpt", "设备启动前应核验防护装置、现场状态和交接记录，确认无异常后方可进入启动准备。");
    draft.put("basicReviewOnly", true);
    when(deepSeek.analyseQuestionJson(anyString(), anyString(), anyInt(), anyString())).thenReturn(
        "{\"design\":{\"assessmentDecision\":\"启动前应先完成哪些核验\",\"conditions\":[\"防护状态待确认\"],\"correctBasis\":[\"核验防护装置、现场状态和交接记录\"],\"plausibleAlternatives\":[\"只核对交接记录\"]}}",
        "{\"question\":{\"sequence\":1,\"stem\":\"设备启动前发现防护装置状态待确认，操作人员应如何处理？\",\"options\":\"A. 核验防护装置、现场状态和交接记录后再准备启动 | B. 仅核对交接记录后准备启动 | C. 先启动设备再观察防护装置状态 | D. 将现场确认交由下一班后离开\",\"answer\":\"A\",\"analysis\":\"启动准备前需要完成防护装置、现场状态和交接记录的核验；防护状态待确认时不能以单项核对替代完整确认。\",\"scoringRubric\":\"答对得2分；答错得0分。\"}}",
        "{\"question\":{\"sequence\":1,\"stem\":\"设备启动前发现防护装置状态待确认，操作人员应如何处理？\",\"options\":\"A. 核验防护装置、现场状态和交接记录后再准备启动 | B. 仅核对交接记录后准备启动 | C. 先启动设备再观察防护装置状态 | D. 将现场确认交由下一班后离开\",\"answer\":\"A\",\"analysis\":\"启动准备前需要完成防护装置、现场状态和交接记录的核验；防护状态待确认时不能以单项核对替代完整确认。\",\"scoringRubric\":\"答对得2分；答错得0分。\"}}");

    var candidate = refiner.generateCandidates(java.util.List.of(draft), "EXPERT").getFirst();

    assertTrue(candidate.valid());
    assertFalse(refiner.deliveryQuestion(candidate.question()).containsKey("_proGenerationPlan"));
    verify(deepSeek, times(3)).analyseQuestionJson(anyString(), anyString(), anyInt(), anyString());
  }

  @Test void truncatedQuestionJsonIsRegeneratedOnceFromTheCompactBrief() {
    var refiner = new com.tikuzhushou.question.QuestionRefinementService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true, 3, true, 1, 6000,
        "high", "high", "high");
    Map<String, Object> draft = new java.util.LinkedHashMap<>();
    draft.put("sequence", 1); draft.put("type", "TRUE_FALSE"); draft.put("difficulty", "EASY");
    draft.put("level", "三级/高级工"); draft.put("assessmentPoint", "启动前确认".repeat(120));
    draft.put("sourceRef", "第1页"); draft.put("sourceExcerpt", "设备启动前应核验防护装置、现场状态和交接记录，确认无异常后方可进入启动准备。");
    draft.put("basicReviewOnly", true);
    when(deepSeek.analyseJson(anyString(), anyString(), anyInt(), anyString())).thenReturn(
        "{\"questions\":[{\"sequence\":1,\"stem\":\"设备启动前应先",
        "{\"questions\":[{\"sequence\":1,\"stem\":\"设备启动前完成防护装置、现场状态和交接记录核验后再进入启动准备。\",\"options\":\"\",\"answer\":\"正确\",\"analysis\":\"启动准备前需要核验防护装置、现场状态和交接记录，并确认无异常后才能进入启动准备。\",\"scoringRubric\":\"答对得2分；答错得0分。\"}]}");

    var candidate = refiner.generateCandidates(java.util.List.of(draft), "FAST").getFirst();

    assertTrue(candidate.valid());
    verify(deepSeek, times(2)).analyseJson(anyString(), anyString(), anyInt(), anyString());
  }

  @Test void traceHeaderIsGeneratedAndForwardedOnApiResponses() throws Exception {
    mvc.perform(get("/api/health").header("X-Request-Id", "trace-test-0001"))
      .andExpect(status().isOk())
      .andExpect(header().string("X-Request-Id", "trace-test-0001"));
    mvc.perform(get("/api/health"))
      .andExpect(status().isOk())
      .andExpect(header().exists("X-Request-Id"));
  }

  @Test void adminActionsAreRecordedInSemanticAuditAndNonAdminsAreRejected() throws Exception {
    String adminAuth = basic("admin", "test-only-password");
    String editor = "audit-editor-" + UUID.randomUUID().toString().substring(0, 6);
    mvc.perform(post("/api/admin/users").header("Authorization", adminAuth)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + editor + "\",\"password\":\"editor-password\",\"roles\":[\"EDITOR\"],\"dailyQuota\":555}"))
      .andExpect(status().isOk());
    var created = jdbc.query("select action,target_name,detail from admin_operation_log where action='USER_CREATE' order by created_at desc limit 1",
        (rs, n) -> Map.of("action", rs.getString("action"), "targetName", rs.getString("target_name"), "detail", rs.getString("detail")));
    assertFalse(created.isEmpty(), "USER_CREATE must be audited");
    assertEquals("USER_CREATE", created.getFirst().get("action"));
    assertTrue(String.valueOf(created.getFirst().get("targetName")).contains(editor), "audit target should be the created user");
    String createDetail = String.valueOf(created.getFirst().get("detail"));
    assertFalse(createDetail.contains("editor-password"), "audit detail must never contain the plaintext password");

    UUID editorId = jdbc.queryForObject("select id from app_users where username=?", UUID.class, editor);
    mvc.perform(patch("/api/admin/users/{id}", editorId).header("Authorization", adminAuth)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"DISABLED\",\"roles\":[\"EDITOR\"],\"dailyQuota\":111}"))
      .andExpect(status().isOk());
    var updated = jdbc.query("select detail from admin_operation_log where action='USER_UPDATE' and target_id=? order by created_at desc limit 1",
        (rs, n) -> rs.getString(1), editorId);
    assertFalse(updated.isEmpty(), "USER_UPDATE must be audited");
    assertTrue(updated.getFirst().contains("DISABLED"), "USER_UPDATE detail must contain status change");

    String viewer = "audit-viewer-" + UUID.randomUUID().toString().substring(0, 6);
    mvc.perform(post("/api/admin/users").header("Authorization", adminAuth)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + viewer + "\",\"password\":\"viewer-password\",\"roles\":[\"VIEWER\"],\"dailyQuota\":1}"))
      .andExpect(status().isOk());
    String viewerAuth = basic(viewer, "viewer-password");
    mvc.perform(get("/api/admin/audit/operations").header("Authorization", viewerAuth))
      .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/audit/operations").header("Authorization", adminAuth))
      .andExpect(status().isOk());
    mvc.perform(get("/api/admin/audit/operations/page?limit=10&offset=0&outcome=SUCCESS").header("Authorization", adminAuth))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items").isArray())
      .andExpect(jsonPath("$.total").isNumber())
      .andExpect(jsonPath("$.hasMore").isBoolean());
    mvc.perform(get("/api/admin/audit/summary").header("Authorization", adminAuth))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.total").isNumber())
      .andExpect(jsonPath("$.security").isNumber());
  }
  @Test void knowledgeBasesRequireAuthentication() throws Exception { mvc.perform(get("/api/knowledge-bases")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.statusCode").value("AUTHENTICATION_REQUIRED")).andExpect(header().doesNotExist("WWW-Authenticate")); }
  @Test void localFrontendOriginsPassCorsPreflight() throws Exception {
    for (String origin : new String[]{"http://127.0.0.1:4173", "http://localhost:4173"}) {
      mvc.perform(options("/api/documents")
          .header("Origin", origin)
          .header("Access-Control-Request-Method", "POST")
          .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", origin))
        .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("POST")));
    }
  }
  @Test void adminCanCreateAndReadKnowledgeBase() throws Exception {
    String authorization = basic("admin", "test-only-password");
    mvc.perform(post("/api/knowledge-bases").header("Authorization",authorization).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"回归知识库\",\"description\":\"测试\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    mvc.perform(get("/api/knowledge-bases").header("Authorization",authorization)).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("回归知识库"));
  }

  @Test void editorGetsAnEmptyPersonalWorkspaceAndCannotReadAdminData() throws Exception {
    String admin = basic("admin", "test-only-password");
    String editor = "isolation-" + UUID.randomUUID().toString().substring(0, 8);
    var adminBase = mvc.perform(post("/api/knowledge-bases").header("Authorization", admin)
        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"管理员专属资料\"}"))
      .andExpect(status().isOk()).andReturn();
    String adminBaseId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(adminBase.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(post("/api/admin/users").header("Authorization", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + editor + "\",\"password\":\"editor-password\",\"roles\":[\"EDITOR\"],\"dailyQuota\":321}"))
      .andExpect(status().isOk());
    String editorAuth = basic(editor, "editor-password");
    mvc.perform(get("/api/auth/me").header("Authorization", editorAuth))
      .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(editor))
      .andExpect(jsonPath("$.roles[0]").value("EDITOR"));
    mvc.perform(get("/api/knowledge-bases").header("Authorization", editorAuth))
      .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
      .andExpect(jsonPath("$[0].name").value("我的个人知识库"))
      .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    mvc.perform(get("/api/knowledge-bases/{id}/documents", adminBaseId).header("Authorization", editorAuth))
      .andExpect(status().isForbidden()).andExpect(jsonPath("$.statusCode").value("ACCESS_DENIED"));
    mvc.perform(get("/api/model-quota").header("Authorization", editorAuth))
      .andExpect(status().isOk()).andExpect(jsonPath("$.accountName").value(editor))
      .andExpect(jsonPath("$.dailyQuota").value(321)).andExpect(jsonPath("$.requests").value(0));
  }

  @Test void errorsExposeStableStatusCode() throws Exception {
    mvc.perform(post("/api/knowledge-bases").header("Authorization", basic("admin", "test-only-password"))
        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.statusCode").value("INVALID_REQUEST"))
      .andExpect(jsonPath("$.httpStatus").value("400"));
  }

  @Test void malformedJsonOnPublicEndpointReturnsBadRequestNotAuthRequired() throws Exception {
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{not-valid-json"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.statusCode").value("INVALID_REQUEST"))
      .andExpect(jsonPath("$.httpStatus").value("400"));
  }

  @Test void assistantPersistsUserAndAssistantMessagesInChatMode() throws Exception {
    String authorization = basic("admin", "test-only-password");
    var created = mvc.perform(post("/api/assistant/conversations").header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"助手回归\",\"mode\":\"CHAT\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("CHAT")).andReturn();
    String conversationId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asText();
    when(deepSeek.analyseJson(anyString(), anyString(), eq(4096)))
      .thenReturn("{\"blocks\":[{\"evidenceType\":\"GENERAL_SUPPLEMENT\",\"segments\":[{\"text\":\"【通用对话，未引用项目资料】这是用于验证消息留存的回复。\",\"sources\":[]}]}]}");
    mvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId).header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"请验证我的提问是否会保存\",\"mode\":\"CHAT\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.userMessage.role").value("USER"))
      .andExpect(jsonPath("$.userMessage.content").value("请验证我的提问是否会保存"))
      .andExpect(jsonPath("$.message.role").value("ASSISTANT"))
      .andExpect(jsonPath("$.message.statusCode").value("OK"))
      .andExpect(jsonPath("$.message.answerBlocks[0].evidenceType").value("GENERAL_SUPPLEMENT"))
      .andExpect(jsonPath("$.message.answerBlocks[0].segments[0].text").value("【通用对话，未引用项目资料】这是用于验证消息留存的回复。"));
    mvc.perform(get("/api/assistant/conversations/{id}/messages", conversationId).header("Authorization", authorization))
      .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
      .andExpect(jsonPath("$[0].role").value("USER"))
      .andExpect(jsonPath("$[1].role").value("ASSISTANT"));
  }

  @Test void emailRegistrationAndOneTimeCodeLoginWorkInLocalDeliveryMode() throws Exception {
    String email = "mail-user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    String username = "mailuser" + UUID.randomUUID().toString().substring(0, 8);
    var issued = mvc.perform(post("/api/auth/email-codes").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.delivery").value("LOCAL_DEVELOPMENT")).andReturn();
    String code = new com.fasterxml.jackson.databind.ObjectMapper().readTree(issued.getResponse().getContentAsString()).get("debugCode").asText();
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"mail-password\",\"code\":\"" + code + "\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REGISTERED"));
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + email + "\",\"password\":\"mail-password\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(username)).andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.emailVerified").value(true));
  }

  @Test void emailCodeRateLimitUsesAStableClientRetryStatus() throws Exception {
    String email = "rate-limit-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    mvc.perform(post("/api/auth/email-codes").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"" + email + "\",\"purpose\":\"LOGIN\"}"))
      .andExpect(status().isOk());
    mvc.perform(post("/api/auth/email-codes").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"" + email + "\",\"purpose\":\"LOGIN\"}"))
      .andExpect(status().isTooManyRequests())
      .andExpect(jsonPath("$.statusCode").value("EMAIL_CODE_RATE_LIMITED"))
      .andExpect(jsonPath("$.httpStatus").value("429"));
  }

  @Test void accountSwitchCreatesARefreshableSessionForTheNewUser() throws Exception {
    String email = "switch-user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    String username = "switchuser" + UUID.randomUUID().toString().substring(0, 8);
    var registerCode = mvc.perform(post("/api/auth/email-codes").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}"))
      .andExpect(status().isOk()).andReturn();
    String code = new com.fasterxml.jackson.databind.ObjectMapper().readTree(registerCode.getResponse().getContentAsString()).get("debugCode").asText();
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"switch-password\",\"code\":\"" + code + "\"}"))
      .andExpect(status().isOk());
    MockHttpSession browserSession = new MockHttpSession();
    var adminLogin = mvc.perform(post("/api/auth/login").session(browserSession).contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"admin\",\"password\":\"test-only-password\"}"))
      .andExpect(status().isOk()).andReturn();
    MockHttpSession adminSession = (MockHttpSession) adminLogin.getRequest().getSession(false);
    var loginCode = mvc.perform(post("/api/auth/email-codes").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"" + email + "\",\"purpose\":\"LOGIN\"}"))
      .andExpect(status().isOk()).andReturn();
    String loginValue = new com.fasterxml.jackson.databind.ObjectMapper().readTree(loginCode.getResponse().getContentAsString()).get("debugCode").asText();
    var editorLogin = mvc.perform(post("/api/auth/login/email-code").session(adminSession).contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"" + email + "\",\"code\":\"" + loginValue + "\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(username)).andReturn();
    MockHttpSession editorSession = (MockHttpSession) editorLogin.getRequest().getSession(false);
    assertNotNull(editorSession); assertNotSame(adminSession, editorSession);
    mvc.perform(get("/api/auth/me").session(editorSession))
      .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(username))
      .andExpect(jsonPath("$.roles[0]").value("EDITOR"));
  }

  @Test void usernameCanChangeWithoutChangingPersonalAccountIdentity() throws Exception {
    String admin = basic("admin", "test-only-password");
    String original = "renameold" + UUID.randomUUID().toString().substring(0, 8);
    String updated = "renamenew" + UUID.randomUUID().toString().substring(0, 8);
    mvc.perform(post("/api/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + original + "\",\"password\":\"editor-password\",\"roles\":[\"EDITOR\"]}"))
      .andExpect(status().isOk());
    var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + original + "\",\"password\":\"editor-password\"}"))
      .andExpect(status().isOk()).andReturn();
    MockHttpSession oldSession = (MockHttpSession) login.getRequest().getSession(false);
    String accountId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(login.getResponse().getContentAsString()).get("id").asText();
    var rename = mvc.perform(put("/api/auth/profile").session(oldSession).contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"  " + updated + "  \"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(updated)).andExpect(jsonPath("$.id").value(accountId)).andReturn();
    MockHttpSession refreshedSession = (MockHttpSession) rename.getRequest().getSession(false);
    assertNotNull(refreshedSession); assertNotSame(oldSession, refreshedSession);
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + original + "\",\"password\":\"editor-password\"}"))
      .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + updated + "\",\"password\":\"editor-password\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(accountId));
    mvc.perform(get("/api/auth/me").session(refreshedSession))
      .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(updated));
  }

  @Test void workflowTaskSupportsCancellationAndOwnerIsolation() throws Exception {
    UUID taskId = UUID.randomUUID();
    UUID adminId = UUID.nameUUIDFromBytes("admin".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update("insert into workflow_tasks(id,owner_id,task_type,status,stage_code,progress,processed_items,total_items,status_message,idempotency_key,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
        taskId, adminId, "DOCUMENT_PARSE", "QUEUED", "QUEUED", 0, 0, 10, "等待测试", "test-" + taskId, now, now);

    mvc.perform(post("/api/workflow-tasks/{id}/cancel", taskId)
        .header("Authorization", basic("admin", "test-only-password")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value("CANCELLED"))
      .andExpect(jsonPath("$.stageCode").value("CANCELLED"));

    String editor = "editor-" + taskId.toString().substring(0, 8);
    mvc.perform(post("/api/admin/users").header("Authorization", basic("admin", "test-only-password"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + editor + "\",\"password\":\"editor-password\",\"roles\":[\"EDITOR\"]}"))
      .andExpect(status().isOk());
    mvc.perform(get("/api/workflow-tasks/{id}", taskId)
        .header("Authorization", basic(editor, "editor-password")))
      .andExpect(status().isForbidden())
      .andExpect(jsonPath("$.statusCode").value("ACCESS_DENIED"));
  }

  @Test void quantityMismatchKeepsAcceptedQuestionsExportable() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID adminId = UUID.nameUUIDFromBytes("admin".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Timestamp now = Timestamp.from(Instant.now());
    String request = "{\"expectedTotal\":3,\"maxRetries\":3}";
    String result = "[{\"sequence\":1,\"type\":\"TRUE_FALSE\",\"assessmentPoint\":\"测试考点\",\"difficulty\":\"EASY\",\"stem\":\"这是用于验证部分题库导出功能的完整判断题题干\",\"options\":\"\",\"answer\":\"正确\",\"analysis\":\"该题依据测试证据验证部分题库仍然可以导出。\",\"sourceRef\":\"第1页\",\"sourceExcerpt\":\"用于自动化测试的原文证据片段，长度足以完成导出格式验证。\"}]";
    jdbc.update("insert into generation_jobs(id,owner_id,job_type,status,progress,request_json,result_json,retry_count,error_message,error_code,stage_code,processed_items,total_items,status_message,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        jobId, adminId, "QUESTION_BANK", "FAILED", 100, request, result, 999, "计划 3 道，实际 1 道",
        "QUESTION_COUNT_MISMATCH", "QUESTION_COUNT_MISMATCH", 1, 3, "允许不完整导出", now, now);
    mvc.perform(get("/api/generation/jobs/{id}/export.xlsx", jobId)
        .header("Authorization", basic("admin", "test-only-password")))
      .andExpect(status().isOk())
      .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("spreadsheetml.sheet")));
    // 兼容旧任务中已经写入的 maxRetries，并验证累计 999 次后仍允许用户主动继续补题。
    mvc.perform(post("/api/generation/jobs/{id}/retry", jobId)
        .header("Authorization", basic("admin", "test-only-password")))
      .andExpect(status().isAccepted())
      .andExpect(jsonPath("$.status").value("QUEUED"));
  }

  @Test void blueprintCanBeEditedWithoutChangingPlannedQuantity() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID adminId = UUID.nameUUIDFromBytes("admin".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Timestamp now = Timestamp.from(Instant.now());
    String request = "{\"total\":2,\"standardId\":\"" + UUID.randomUUID() + "\",\"knowledgeBaseId\":\"" + UUID.randomUUID() + "\"}";
    String result = "[{\"sequence\":1,\"type\":\"SINGLE_CHOICE\",\"difficulty\":\"EASY\",\"assessmentPoint\":\"原考点一\"},{\"sequence\":2,\"type\":\"TRUE_FALSE\",\"difficulty\":\"MEDIUM\",\"assessmentPoint\":\"原考点二\"}]";
    jdbc.update("insert into generation_jobs(id,owner_id,job_type,status,progress,request_json,result_json,retry_count,error_message,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
        jobId, adminId, "BLUEPRINT", "SUCCEEDED", 100, request, result, 0, null, now, now);
    String edited = "{\"items\":[{\"sequence\":1,\"type\":\"MULTIPLE_CHOICE\",\"difficulty\":\"HARD\",\"assessmentPoint\":\"编辑后的考点一\"},{\"sequence\":2,\"type\":\"TRUE_FALSE\",\"difficulty\":\"MEDIUM\",\"assessmentPoint\":\"编辑后的考点二\"}]}";
    mvc.perform(put("/api/generation/blueprints/{id}", jobId).header("Authorization", basic("admin", "test-only-password"))
        .contentType(MediaType.APPLICATION_JSON).content(edited))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.result.length()").value(2))
      .andExpect(jsonPath("$.result[0].type").value("MULTIPLE_CHOICE"))
      .andExpect(jsonPath("$.result[0].cognitiveTarget").value("ANALYZE_DECIDE"))
      .andExpect(jsonPath("$.result[0].assessmentPoint").value("编辑后的考点一"));
  }

  @Test void questionReviewCreatesAuditTrailAndUnlocksDeliveryPreflight() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID adminId = UUID.nameUUIDFromBytes("admin".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Timestamp now = Timestamp.from(Instant.now());
    String request = "{\"expectedTotal\":1,\"generationMode\":\"BALANCED\"}";
    String result = "[{\"sequence\":1,\"type\":\"TRUE_FALSE\",\"assessmentPoint\":\"作业前安全检查\",\"difficulty\":\"MEDIUM\",\"stem\":\"作业人员在设备启动之前完成规定的安全检查能够降低现场事故风险。\",\"options\":\"\",\"answer\":\"正确\",\"analysis\":\"依据作业前安全检查要求，设备启动前必须确认防护装置与作业环境状态。\",\"sourceRef\":\"安全作业要求第1条\",\"sourceExcerpt\":\"作业前安全检查包括确认防护装置、设备状态和作业环境，检查完成后方可启动设备。\"}]";
    jdbc.update("insert into generation_jobs(id,owner_id,job_type,status,progress,request_json,result_json,retry_count,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?)",
        jobId, adminId, "QUESTION_BANK", "SUCCEEDED", 100, request, result, 0, now, now);
    String authorization = basic("admin", "test-only-password");

    mvc.perform(get("/api/reviews/jobs/{id}", jobId).header("Authorization", authorization))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].status").value("AI_DRAFT"))
      .andExpect(jsonPath("$[0].question.stem").exists());
    mvc.perform(patch("/api/reviews/jobs/{id}/items/1", jobId).header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"APPROVED\",\"locked\":false,\"comment\":\"自动化审核通过\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value("APPROVED"))
      .andExpect(jsonPath("$.version").value(2));
    mvc.perform(get("/api/reviews/jobs/{id}/items/1/events", jobId).header("Authorization", authorization))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].comment").value("自动化审核通过"));
    mvc.perform(get("/api/quality/jobs/{id}/preflight", jobId).header("Authorization", authorization))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.deliveryReady").value(true))
      .andExpect(jsonPath("$.approvedReviews").value(1));
  }

  @Test void productionHealthDetailsAreVisibleOnlyToAdmin() throws Exception {
    mvc.perform(get("/api/admin/system/health")
        .header("Authorization", basic("admin", "test-only-password")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.components.database.status").value("UP"))
      .andExpect(jsonPath("$.components.pgvector.status").value("UP"));
    mvc.perform(get("/api/admin/system/health"))
      .andExpect(status().isUnauthorized());
  }

  @Test void excelImportRunsValidationBeforePersisting() throws Exception {
    byte[] workbook;
    try (var output = new java.io.ByteArrayOutputStream(); var excel = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
      var sheet = excel.createSheet("题库"); var header = sheet.createRow(0);
      String[] columns = {"序号", "题型", "考点", "难度", "题干", "选项", "答案", "解析", "原文定位", "原文片段"};
      for (int index = 0; index < columns.length; index++) header.createCell(index).setCellValue(columns[index]);
      var row = sheet.createRow(1); row.createCell(0).setCellValue(1); row.createCell(1).setCellValue("UNSUPPORTED");
      row.createCell(2).setCellValue("安全作业"); row.createCell(3).setCellValue("MEDIUM");
      row.createCell(4).setCellValue("这是一道用于检查 Excel 导入前置校验机制的完整测试题干");
      row.createCell(6).setCellValue(""); row.createCell(7).setCellValue("解析内容完整但题型与答案无效");
      excel.write(output); workbook = output.toByteArray();
    }
    var file = new org.springframework.mock.web.MockMultipartFile("file", "invalid.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);
    String authorization = basic("admin", "test-only-password");
    mvc.perform(multipart("/api/excel/questions/validate").file(file).header("Authorization", authorization))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.valid").value(false))
      .andExpect(jsonPath("$.errorCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
      .andExpect(jsonPath("$.issues[0].code").exists());
    mvc.perform(multipart("/api/excel/questions/import").file(file).header("Authorization", authorization))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("EXCEL_VALIDATION_FAILED")));
  }

  @Test void tableDetectionUsesLocalTextAndGridHeuristics() {
    String aligned = "序号    工作内容    技能要求\n1    原料加工    符合规范\n2    安全检查    操作正确\n3    质量控制    记录完整";
    Boolean textDetected = org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing, "looksLikeTable", aligned);
    org.junit.jupiter.api.Assertions.assertTrue(Boolean.TRUE.equals(textDetected));
    Boolean compactHeaderDetected = org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing, "looksLikeTable",
        "3.3 高级\n职业功能 工作内容 技能要求 相关知识\n正文内容");
    org.junit.jupiter.api.Assertions.assertTrue(Boolean.TRUE.equals(compactHeaderDetected));

    var image = new java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics(); graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 600, 800);
    graphics.setColor(java.awt.Color.BLACK);
    for (int y : new int[]{100, 260, 420, 580, 700}) graphics.drawLine(30, y, 570, y);
    for (int x : new int[]{30, 220, 400, 570}) graphics.drawLine(x, 100, x, 700);
    graphics.dispose();
    Boolean imageDetected = org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing, "imageLooksTabular", image);
    org.junit.jupiter.api.Assertions.assertTrue(Boolean.TRUE.equals(imageDetected));

    var blank = new java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var blankGraphics = blank.createGraphics(); blankGraphics.setColor(java.awt.Color.WHITE);
    blankGraphics.fillRect(0, 0, 600, 800); blankGraphics.dispose();
    Boolean blankDetected = org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing, "isVisuallyBlank", blank);
    assertTrue(Boolean.TRUE.equals(blankDetected));
    var pageNumberGraphics = blank.createGraphics(); pageNumberGraphics.setColor(java.awt.Color.BLACK);
    pageNumberGraphics.drawString("9", 300, 760); pageNumberGraphics.dispose();
    Boolean pageNumberDetected = org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing, "isVisuallyBlank", blank);
    assertFalse(Boolean.TRUE.equals(pageNumberDetected));

    var normalizedTable = new com.tikuzhushou.document.DocumentParsingService.ParsedTable(UUID.randomUUID(), 2, 0, "",
        java.util.List.of("职业功能", "工作内容"), java.util.List.of(java.util.List.of("膳食评价", "食谱设计\n操作指导")),
        "", .9, "AI_EXTRACTED", java.util.List.of(), Instant.now(), "", java.util.List.of(), java.util.List.of(), "TEST");
    String normalizedMarkdown = org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing,
        "normalizeStructuredMarkdown", 2, "[第2页]\n| 列1 | 列2 |\n| --- | --- |\n| 膳食评价 | 食谱设计 |", java.util.List.of(normalizedTable));
    org.junit.jupiter.api.Assertions.assertTrue(normalizedMarkdown.contains("| 职业功能 | 工作内容 |"));
    org.junit.jupiter.api.Assertions.assertFalse(normalizedMarkdown.contains("| 列1 | 列2 |"));
    org.junit.jupiter.api.Assertions.assertTrue(normalizedMarkdown.contains("食谱设计<br>操作指导"));
  }

  @Test void trailingBlankPdfPageSkipsOcrWithoutAcceptingBlankFirstPages() throws Exception {
    var originalOcr = org.springframework.test.util.ReflectionTestUtils.getField(parsing, "ocr");
    java.nio.file.Path pdf = java.nio.file.Files.createTempFile("tiku-trailing-blank-", ".pdf");
    try {
      org.springframework.test.util.ReflectionTestUtils.setField(parsing, "ocr",
          new com.tikuzhushou.ocr.VisionOcrService(deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true));
      try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
        document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
        try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, document.getPage(0))) {
          content.beginText();
          content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
              org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
          content.newLineAtOffset(72, 720);
          content.showText("This source page contains enough ordinary text to be accepted without remote OCR. ".repeat(3));
          content.endText();
        }
        document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
        document.save(pdf.toFile());
      }

      Object extraction = org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing, "extract", pdf,
          "application/pdf", (com.tikuzhushou.document.DocumentParsingService.ProgressListener) (stage, progress, processed, total, message) -> { });
      Integer unresolved = org.springframework.test.util.ReflectionTestUtils.invokeMethod(extraction, "unresolvedPages");
      String text = org.springframework.test.util.ReflectionTestUtils.invokeMethod(extraction, "text");
      @SuppressWarnings("unchecked")
      java.util.List<String> warnings = org.springframework.test.util.ReflectionTestUtils.invokeMethod(extraction, "warnings");
      assertEquals(0, unresolved);
      assertTrue(text.contains("[第2页]"));
      assertTrue(warnings.stream().anyMatch(value -> value.contains("第2页为空白页")));
      verifyNoInteractions(deepSeek);
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(parsing, "ocr", originalOcr);
      java.nio.file.Files.deleteIfExists(pdf);
    }
  }

  @Test void cadPdfSkipsGenericTableOcrAndKeepsPageReviewMarker() throws Exception {
    var originalOcr = org.springframework.test.util.ReflectionTestUtils.getField(parsing, "ocr");
    java.nio.file.Path pdf = java.nio.file.Files.createTempFile("tiku-cad-drawing-", ".pdf");
    try {
      org.springframework.test.util.ReflectionTestUtils.setField(parsing, "ocr",
          new com.tikuzhushou.ocr.VisionOcrService(deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true));
      try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
        document.getDocumentInformation().setProducer("CAXA CAD");
        document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
        document.save(pdf.toFile());
      }

      Object extraction = org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing, "extract", pdf,
          "application/pdf", (com.tikuzhushou.document.DocumentParsingService.ProgressListener) (stage, progress, processed, total, message) -> { });
      Integer unresolved = org.springframework.test.util.ReflectionTestUtils.invokeMethod(extraction, "unresolvedPages");
      String text = org.springframework.test.util.ReflectionTestUtils.invokeMethod(extraction, "text");
      @SuppressWarnings("unchecked")
      java.util.List<String> warnings = org.springframework.test.util.ReflectionTestUtils.invokeMethod(extraction, "warnings");
      assertEquals(0, unresolved);
      assertTrue(text.contains("工程图页面"));
      assertTrue(warnings.stream().anyMatch(value -> value.contains("CAD 工程图 PDF")));
      String qualityText = org.springframework.test.util.ReflectionTestUtils.invokeMethod(extraction, "qualityText");
      assertTrue(qualityText.isBlank());
      verifyNoInteractions(deepSeek);
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(parsing, "ocr", originalOcr);
      java.nio.file.Files.deleteIfExists(pdf);
    }
  }

  @Test void embeddedDrawingPageDetectorUsesImageAndEngineeringTextTogether() throws Exception {
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      document.addPage(page);
      var image = new java.awt.image.BufferedImage(1200, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
      var graphics = image.createGraphics(); graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 1200, 800);
      graphics.setColor(java.awt.Color.BLACK); graphics.drawRect(40, 40, 1120, 700); graphics.drawLine(200, 400, 1000, 400); graphics.dispose();
      try (var output = new java.io.ByteArrayOutputStream()) {
        javax.imageio.ImageIO.write(image, "png", output);
        var xObject = org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(document, output.toByteArray(), "drawing");
        try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
          content.drawImage(xObject, 72, 120, 420, 280);
        }
      }
      Boolean detected = org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing,
          "looksLikeEngineeringDrawingPage", page, "AutoCAD 零件图 技术要求", "AutoCAD 零件图 技术要求");
      Boolean textOnly = org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing,
          "looksLikeEngineeringDrawingPage", page, "本页说明 AutoCAD 软件操作", "本页说明 AutoCAD 软件操作");
      assertTrue(Boolean.TRUE.equals(detected));
      assertFalse(Boolean.TRUE.equals(textOnly));
    }
  }

  @Test void visionTableJsonSupportsPngAndJpegInputs() {
    var service = new com.tikuzhushou.ocr.VisionOcrService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true);
    String payload = """
        {"hasTable":true,"markdown":"[第1页]\\n[TABLE_0]","confidence":0.97,"warnings":[],"tables":[
          {"title":"测试表","confidence":0.96,"warnings":[],"cells":[
            {"row":0,"column":0,"rowSpan":1,"columnSpan":1,"text":"项目","confidence":0.99},
            {"row":0,"column":1,"rowSpan":1,"columnSpan":1,"text":"要求","confidence":0.99},
            {"row":1,"column":0,"rowSpan":2,"columnSpan":1,"text":"安全","confidence":0.97},
            {"row":1,"column":1,"rowSpan":1,"columnSpan":1,"text":"规范","confidence":0.96},
            {"row":2,"column":1,"rowSpan":1,"columnSpan":1,"text":"完整","confidence":0.95}
          ]}]}
        """;
    for (String mime : java.util.List.of("image/png", "image/jpeg")) {
      when(deepSeek.analyseImagesJson(anyString(), anyList(), eq(mime))).thenReturn(payload);
      var result = service.recognizeStructuredPage(1, java.util.List.of(new byte[]{1, 2, 3}), mime, "");
      assertTrue(result.hasTable());
      assertEquals(2, result.tables().getFirst().cells().get(2).rowSpan());
      verify(deepSeek).analyseImagesJson(anyString(), anyList(), eq(mime));
    }
  }

  @Test void tableRegionOcrUsesHighThinkingAndReturnsOnlyCellStructure() {
    var service = new com.tikuzhushou.ocr.VisionOcrService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true);
    when(deepSeek.analyseImagesStructured(anyString(), anyList(), eq("image/jpeg"), eq(true))).thenReturn("""
        {"body":"职业技能等级标准","warnings":[],"tables":[
          {"title":"技能要求","confidence":0.96,"warnings":[],"cells":[
            {"row":0,"column":0,"rowSpan":1,"columnSpan":1,"text":"项目","confidence":0.99},
            {"row":0,"column":1,"rowSpan":1,"columnSpan":1,"text":"要求","confidence":0.99},
            {"row":1,"column":0,"rowSpan":1,"columnSpan":1,"text":"安全","confidence":0.96},
            {"row":1,"column":1,"rowSpan":1,"columnSpan":1,"text":"操作规范","confidence":0.96}
          ]}]}
        """);

    var result = service.recognizeTableRegions(3, java.util.List.of(new byte[]{1}, new byte[]{2}), "image/jpeg",
        "项目 要求 安全 操作规范", "上一页表头：项目｜要求");

    assertTrue(result.body().startsWith("[第3页]"));
    assertEquals("操作规范", result.tables().getFirst().rows().getFirst().get(1));
    verify(deepSeek).analyseImagesStructured(anyString(), anyList(), eq("image/jpeg"), eq(true));
    verify(deepSeek, never()).analyseImagesStructured(anyString(), anyList(), eq("image/jpeg"), eq(false));
  }

  @Test void readingOcrUsesPreviousPageContextAndReturnsMarkdownTable() {
    var service = new com.tikuzhushou.ocr.VisionOcrService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true);
    when(deepSeek.analyseImagesReading(anyString(), anyList(), eq("image/png"), eq(true))).thenReturn("""
        ```markdown
        [第5页]

        ### 3.1 初级（续）

        | 职业功能 | 工作内容 | 技能要求 | 相关知识 |
        | --- | --- | --- | --- |
        | 三、菜肴制作（续） | （二）烹制本菜系风味菜肴 | 准确掌握火候 | 调味原则 |
        ```
        """);
    String result = service.recognizeReadingPage(5, java.util.List.of(new byte[]{1, 2}), "image/png",
        "肴 制 作", "[第4页]\n### 3.2 中级\n[结构化表格:1]\n三、菜肴制作\n（一）烹制一般菜肴");
    assertTrue(result.startsWith("[第5页]"));
    assertTrue(result.contains("### 3.2 中级（续）"));
    assertFalse(result.contains("### 3.1 初级（续）"));
    assertTrue(result.contains("三、菜肴制作（续）"));
    var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(deepSeek).analyseImagesReading(prompt.capture(), anyList(), eq("image/png"), eq(true));
    assertTrue(prompt.getValue().contains("三、菜肴制作"));
    assertTrue(prompt.getValue().contains("肴 制 作"));
  }

  @Test void readingMarkdownTablesBecomeSafePreviewMarkers() {
    String markdown = """
        [第5页]
        ### 3.3 高级
        | 职业功能 | 工作内容 |
        | --- | --- |
        | 原料加工 | 质量鉴别 |
        """;
    String marked = org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing,
        "markdownWithTableMarkers", 5, markdown);
    assertTrue(marked.contains("[结构化表格:0]"));
    assertFalse(marked.contains("| --- |"));
  }

  @Test void tableOcrFallsBackToReadingProjectionWhenCellJsonIsInvalid() {
    var originalOcr = org.springframework.test.util.ReflectionTestUtils.getField(parsing, "ocr");
    var originalTableMode = org.springframework.test.util.ReflectionTestUtils.getField(parsing, "visionTableEnabled");
    try {
      org.springframework.test.util.ReflectionTestUtils.setField(parsing, "visionTableEnabled", true);
      org.springframework.test.util.ReflectionTestUtils.setField(parsing, "ocr",
          new com.tikuzhushou.ocr.VisionOcrService(deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true));
      when(deepSeek.analyseImagesStructured(anyString(), anyList(), eq("image/jpeg"), anyBoolean())).thenReturn("{");
      when(deepSeek.analyseImagesReading(anyString(), anyList(), eq("image/jpeg"), eq(true))).thenReturn("""
          [第1页]
          ### 技能要求
          | 项目 | 要求 |
          | --- | --- |
          | 安全检查 | 操作规范 |
          """);

      var image = new java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
      Object result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing, "extractVisionTables",
          1, image, "项目 要求 安全检查 操作规范", "", new java.util.ArrayList<String>());

      assertFalse(Boolean.TRUE.equals(org.springframework.test.util.ReflectionTestUtils.invokeMethod(result, "needsReview")));
      String markdown = org.springframework.test.util.ReflectionTestUtils.invokeMethod(result, "markdown");
      @SuppressWarnings("unchecked")
      java.util.List<com.tikuzhushou.document.DocumentParsingService.ParsedTable> tables =
          org.springframework.test.util.ReflectionTestUtils.invokeMethod(result, "tables");
      assertTrue(markdown.contains("[结构化表格:0]"));
      assertFalse(markdown.contains("| --- |"));
      assertEquals("DEEPSEEK_READING", tables.getFirst().extractionSource());
      assertTrue(tables.getFirst().warnings().getFirst().contains("阅读式 OCR 回退"));
      verify(deepSeek, times(2)).analyseImagesStructured(anyString(), anyList(), eq("image/jpeg"), anyBoolean());
      verify(deepSeek).analyseImagesReading(anyString(), anyList(), eq("image/jpeg"), eq(true));
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(parsing, "visionTableEnabled", originalTableMode);
      org.springframework.test.util.ReflectionTestUtils.setField(parsing, "ocr", originalOcr);
    }
  }

  @Test void readingOcrFallsBackToFastModeAfterEmptyThinkingResponse() {
    var service = new com.tikuzhushou.ocr.VisionOcrService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true);
    when(deepSeek.analyseImagesReading(anyString(), anyList(), eq("image/jpeg"), eq(true))).thenReturn("");
    when(deepSeek.analyseImagesReading(anyString(), anyList(), eq("image/jpeg"), eq(false))).thenReturn("""
        [第1页]
        | 项目 | 要求 |
        | --- | --- |
        | 安全 | 操作规范 |
        """);
    String result = service.recognizeReadingPage(1, java.util.List.of(new byte[]{1}), "image/jpeg", "项目 要求", "");
    assertTrue(result.contains("| 安全 | 操作规范 |"));
    verify(deepSeek).analyseImagesReading(anyString(), anyList(), eq("image/jpeg"), eq(true));
    verify(deepSeek).analyseImagesReading(anyString(), anyList(), eq("image/jpeg"), eq(false));
  }

  @Test void readingOcrRepairsOnlyTheMissingTableMarkerAfterBothFullPageAttempts() {
    var service = new com.tikuzhushou.ocr.VisionOcrService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true);
    when(deepSeek.analyseImagesReading(anyString(), anyList(), eq("image/png"), eq(true))).thenReturn("[第2页]\n已有正文");
    when(deepSeek.analyseImagesReading(anyString(), anyList(), eq("image/png"), eq(false))).thenReturn("[第2页]\n已有正文");
    when(deepSeek.analyseImagesReadingPatch(anyString(), anyList(), eq("image/png"))).thenReturn("""
        {"patches":[{"marker":"[[OCR_TABLE_REBUILD]]","replacement":"| 项目 | 要求 |\\n| --- | --- |\\n| 安全 | 操作规范 |"}]}
        """);

    String result = service.recognizeReadingPage(2, java.util.List.of(new byte[]{1}), "image/png", "项目 要求", "");

    assertTrue(result.contains("已有正文"));
    assertTrue(result.contains("| 安全 | 操作规范 |"));
    assertFalse(result.contains("[[OCR_TABLE_REBUILD]]"));
    verify(deepSeek).analyseImagesReadingPatch(anyString(), anyList(), eq("image/png"));
  }

  @Test void readingProjectionJoinsCategoryLabelsSplitAcrossPdfPages() {
    var prior = new com.tikuzhushou.document.DocumentParsingService.ParsedTable(UUID.randomUUID(), 4, 1, "",
        java.util.List.of("职业功能", "工作内容"), java.util.List.of(java.util.List.of("三、<br>菜", "（一）初步熟处理")),
        "", .9, "AI_EXTRACTED", java.util.List.of(), Instant.now(), "", java.util.List.of(), java.util.List.of(), "DEEPSEEK_READING");
    var current = new com.tikuzhushou.document.DocumentParsingService.ParsedTable(UUID.randomUUID(), 5, 0, "",
        java.util.List.of("职业功能", "工作内容"), java.util.List.of(java.util.List.of("肴制作", "（二）烹制风味菜肴")),
        "", .9, "AI_EXTRACTED", java.util.List.of(), Instant.now(), "", java.util.List.of(), java.util.List.of(), "DEEPSEEK_READING");
    java.util.List<com.tikuzhushou.document.DocumentParsingService.ParsedTable> result =
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(parsing, "stitchReadingContinuation",
            java.util.List.of(current), java.util.List.of(prior));
    assertNotNull(result);
    assertEquals("三、菜肴制作（续）", result.getFirst().rows().getFirst().getFirst());
    assertTrue(result.getFirst().cells().stream().allMatch(cell -> cell.rowSpan() == 1 && cell.columnSpan() == 1));
  }

  @Test void visionTableCandidateRejectsSilentFalseNegative() {
    var service = new com.tikuzhushou.ocr.VisionOcrService(
        deepSeek, new com.fasterxml.jackson.databind.ObjectMapper(), true);
    when(deepSeek.analyseImagesJson(anyString(), anyList(), eq("image/png")))
        .thenReturn("{\"hasTable\":false,\"markdown\":\"[第1页]\\n正文\",\"confidence\":0.8,\"warnings\":[],\"tables\":[]}");
    assertThrows(IllegalStateException.class,
        () -> service.recognizeStructuredPage(1, java.util.List.of(new byte[]{1}), "image/png", "职业功能 工作内容 技能要求 相关知识"));
    verify(deepSeek, times(2)).analyseImagesJson(anyString(), anyList(), eq("image/png"));
  }

  @Test void structuredTableCanBeReviewedAndRebuildsTableChunks() throws Exception {
    UUID adminId = UUID.nameUUIDFromBytes("admin".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID baseId = UUID.randomUUID(), documentId = UUID.randomUUID(), tableId = UUID.randomUUID(); Timestamp now = Timestamp.from(Instant.now());
    jdbc.update("insert into knowledge_bases(id,name,description,owner_id,status,created_at,updated_at) values(?,?,?,?,?,?,?)",
        baseId, "表格回归知识库", "自动化测试", adminId, "ACTIVE", now, now);
    jdbc.update("insert into source_documents(id,knowledge_base_id,original_name,media_type,storage_key,size_bytes,status,parse_mode,extracted_text,parse_quality,parse_warnings,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        documentId, baseId, "table-test.pdf", "application/pdf", "missing-for-api-test.pdf", 100L, "PARSED", "VISION_TABLE",
        "[第1页]\n测试文档中的结构化表格。\n\n[结构化表格:0]", 95d, "[]", now, now);
    String markdown = "| 项目 | 要求 |\n| --- | --- |\n| 安全检查 | 操作正确 |";
    jdbc.update("insert into document_tables(id,document_id,page_no,table_index,title,headers_json,rows_json,markdown,confidence,review_status,warnings_json,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        tableId, documentId, 1, 0, "技能要求表", "[\"项目\",\"要求\"]", "[[\"安全检查\",\"操作正确\"]]", markdown,
        .82d, "AI_EXTRACTED", "[]", now, now);
    jdbc.update("insert into document_table_cells(id,table_id,row_no,column_no,row_span,column_span,cell_text,confidence,bbox_json,source) values(?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(), tableId, 1, 0, 2, 1, "安全检查", .98d, "[]", "DEEPSEEK_VISION");
    String authorization = basic("admin", "test-only-password");
    mvc.perform(get("/api/documents/{id}/tables", documentId).header("Authorization", authorization))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].headers[0]").value("项目"))
      .andExpect(jsonPath("$[0].cells[0].rowSpan").value(2))
      .andExpect(jsonPath("$[0].cells[0].source").value("DEEPSEEK_VISION"));
    mvc.perform(get("/api/documents/{id}/pages/1/content", documentId).header("Authorization", authorization))
      .andExpect(status().isOk()).andExpect(jsonPath("$.blocks[1].type").value("TABLE"))
      .andExpect(jsonPath("$.blocks[1].tableId").value(tableId.toString()));
    String edited = "| 项目 | 要求 |\n| --- | --- |\n| 安全检查 | 启动前必须完成并记录 |\n| 质量控制 | 结果应当可追溯 |";
    mvc.perform(patch("/api/documents/{documentId}/tables/{tableId}", documentId, tableId)
        .header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
        .content("{\"markdown\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(edited) + "}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.reviewStatus").value("HUMAN_EDITED"))
      .andExpect(jsonPath("$.rows.length()").value(2));
    Integer tableChunks = jdbc.queryForObject("select count(*) from document_chunks where document_id=? and content_type='TABLE'", Integer.class, documentId);
    org.junit.jupiter.api.Assertions.assertTrue(tableChunks != null && tableChunks > 0);
  }

  @Test void markdownPagesRequireConfirmationAndKeepVersionHistory() throws Exception {
    UUID adminId = UUID.nameUUIDFromBytes("admin".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID baseId = UUID.randomUUID(), documentId = UUID.randomUUID(); Timestamp now = Timestamp.from(Instant.now());
    jdbc.update("insert into knowledge_bases(id,name,description,owner_id,status,created_at,updated_at) values(?,?,?,?,?,?,?)",
        baseId, "Markdown 回归知识库", "自动化测试", adminId, "ACTIVE", now, now);
    jdbc.update("insert into source_documents(id,knowledge_base_id,original_name,media_type,storage_key,size_bytes,status,parse_mode,extracted_text,parse_quality,parse_warnings,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        documentId, baseId, "markdown-test.pdf", "application/pdf", "missing-for-api-test.pdf", 100L, "PARSED", "TEXT",
        "[第1页]\n1. 职业概况\n这是用于 Markdown 页面回归测试的正文。", 96d, "[]", now, now);
    String authorization = basic("admin", "test-only-password");
    mvc.perform(get("/api/documents/{id}/pages", documentId).header("Authorization", authorization))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].markdown").value(org.hamcrest.Matchers.containsString("# 1 职业概况")))
      .andExpect(jsonPath("$[0].version").value(1));
    String edited = "[第1页]\n\n# 1 职业概况\n\n人工核对后的完整正文。";
    String bodyWithoutConfirmation = "{\"markdown\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(edited)
        + ",\"confirmed\":false,\"rebuildVectors\":false}";
    mvc.perform(patch("/api/documents/{id}/pages/1", documentId).header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON).content(bodyWithoutConfirmation))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PAGE_CONFIRMATION_REQUIRED")));
    String body = "{\"markdown\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(edited)
        + ",\"confirmed\":true,\"rebuildVectors\":false}";
    mvc.perform(patch("/api/documents/{id}/pages/1", documentId).header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_JSON).content(body))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.page.reviewStatus").value("HUMAN_CONFIRMED"))
      .andExpect(jsonPath("$.page.version").value(2))
      .andExpect(jsonPath("$.vectorsRebuilt").value(false));
    mvc.perform(get("/api/documents/{id}/pages/1/versions", documentId).header("Authorization", authorization))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.length()").value(2))
      .andExpect(jsonPath("$[0].action").value("HUMAN_CONFIRM"));
  }
}
