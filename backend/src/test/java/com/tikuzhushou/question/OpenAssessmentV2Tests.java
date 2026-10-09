package com.tikuzhushou.question;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.ai.DeepSeekService;
import com.tikuzhushou.ai.ModelResponseException;
import com.tikuzhushou.assistant.DeepSeekWebSearchService;
import com.tikuzhushou.project.KnowledgeVisualService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenAssessmentV2Tests {
  private final DeepSeekService ai = mock(DeepSeekService.class);
  private final DeepSeekWebSearchService web = mock(DeepSeekWebSearchService.class);
  private final KnowledgeVisualService visuals = mock(KnowledgeVisualService.class);
  private final ObjectMapper json = new ObjectMapper();
  private final OpenAssessmentService service = new OpenAssessmentService(ai, json, visuals, web);

  @Test void generalKnowledgeDoesNotNeedSourceOverlapOrSourceReference() {
    authorReturns(author());
    var candidate = refiner().generateCandidates(List.of(draft()), "FAST").getFirst();
    assertThat(candidate.valid()).isTrue();
    assertThat(candidate.question().get("sourceExcerpt")).isNull();
    assertThat(refiner().deliveryQuestion(candidate.question())).doesNotContainKey("_assessmentResearchBudget");
    verifyNoInteractions(web);
  }

  @Test void absoluteWordsAndShortOptionsAreNotMechanicalReasonsToReject() {
    authorReturns(author());
    var candidate = refiner().generateCandidates(List.of(draft()), "FAST").getFirst();
    assertThat(candidate.question().get("options")).asString().contains("无需");
    assertThat(candidate.valid()).isTrue();
  }

  @Test void solverNeverReceivesAuthorAnswerAnalysisRubricOrBackground() {
    var question = question();
    question.put("answer", "B"); question.put("analysis", "AUTHOR_SECRET_ANALYSIS");
    question.put("scoringRubric", "AUTHOR_SECRET_RUBRIC");
    question.put("_assessmentCorpusContext", "PRIVATE_SOURCE_WITH_ANSWER");
    question.put("_assessmentDesign", Map.of("criticalClaims", List.of("AUTHOR_SECRET_CLAIM")));
    solveReturns(solution("B")); judgeReturns(judgment());
    assertThat(service.review(question).passed()).isTrue();
    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(ai).analyseJson(anyString(), prompt.capture(), anyInt(), anyString(), eq("ASSESSMENT_V2_SOLVE"));
    assertThat(prompt.getValue()).contains("模型长度").doesNotContain("AUTHOR_SECRET", "PRIVATE_SOURCE_WITH_ANSWER", "\"answer\":\"B\"");
  }

  @Test void disagreementFailsEvenWhenJudgeSaysPass() {
    solveReturns(solution("C")); judgeReturns(judgment());
    var review = service.review(question());
    assertThat(review.passed()).isFalse();
    assertThat(review.flags()).contains("INDEPENDENT_ANSWER_MISMATCH");
    assertThat(review.available()).isTrue();
  }

  @Test void legitimateOpenEndedAlternativesAreNotComparedAsAnswerStrings() {
    var question = question(); question.put("type", "SHORT_ANSWER");
    question.put("answer", "建立对照组以排除温度变化的影响");
    solveReturns(solution("保持温度不变，比较实验组与对照组")); judgeReturns(judgment());
    assertThat(service.review(question).passed()).isTrue();
  }

  @Test void intentionalUncertaintyInOpenTaskIsReviewedSemantically() {
    var question = question(); question.put("type", "COMPREHENSIVE");
    question.put("stem", "请指出未给定的结构，并提出需要补充的视图");
    question.put("scoringRubric", "指出未给定的结构并合理提出补充视图得分");
    solveReturns(solution("指出信息不足并提出补充视图方案")
        .replace("\"ambiguities\":[]", "\"ambiguities\":[\"未指定卡扣周向分布，这是本题所问\"]")
        .replace("\"unresolvedFacts\":[]", "\"unresolvedFacts\":[\"图中未给的结构\"]"));
    judgeReturns(judgment().replace("\"flags\":[]", "\"issueResolutions\":["
        + "{\"id\":\"A1\",\"status\":\"ASKED_UNCERTAINTY\",\"reason\":\"本题正是考查缺失结构\",\"stemEvidence\":\"请指出未给定的结构\",\"rubricEvidence\":\"指出未给定的结构\"},"
        + "{\"id\":\"U1\",\"status\":\"ASKED_UNCERTAINTY\",\"reason\":\"评分接纳指出缺失\",\"stemEvidence\":\"请指出未给定的结构\",\"rubricEvidence\":\"指出未给定的结构\"}],\"flags\":[]"));
    assertThat(service.review(question).passed()).isTrue();
  }

  @Test void genuinelyUnanswerableOpenTaskStillFails() {
    var question = question(); question.put("type", "SHORT_ANSWER");
    solveReturns(solution("缺少决定结论的条件").replace("\"answerable\":true", "\"answerable\":false"));
    judgeReturns(judgment());
    assertThat(service.review(question).flags()).contains("INDEPENDENT_ANSWER_UNRESOLVED");
  }

  @Test void ambiguityInClosedQuestionRemainsABlocker() {
    solveReturns(solution("B").replace("\"ambiguities\":[]", "\"ambiguities\":[\"A在另一合理解释下也成立\"]"));
    judgeReturns(judgment());
    assertThat(service.review(question()).flags()).contains("INDEPENDENT_AMBIGUITY");
  }

  @Test void feedbackIsCompactButFullIndependentSolutionIsRetainedForAudit() {
    String longAnswer = "需核实条件".repeat(400);
    var question = question(); solveReturns(solution(longAnswer)); judgeReturns(judgment());
    var review = service.review(question);
    assertThat(review.feedback()).hasSizeLessThan(1000);
    assertThat(question.get("_assessmentIndependentSolution").toString()).contains(longAnswer);
  }

  @Test void missingSemanticChecksDoNotPass() {
    solveReturns(solution("B")); judgeReturns("{\"action\":\"FINAL\",\"status\":\"PASS\"}");
    assertThat(service.review(question()).passed()).isFalse();
  }

  @Test void incompleteChoiceChecksUseOneBoundedProtocolRecovery() {
    solveReturns(solution("B"));
    String incomplete = judgment().replace("\"optionChecks\":{\"A\":\"PASS\",\"B\":\"PASS\",\"C\":\"PASS\",\"D\":\"PASS\"},", "");
    judgeReturns(incomplete);
    when(ai.analyseJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_V2_JUDGE_PROTOCOL_RECOVERY")))
        .thenReturn(judgment());
    var review = service.review(question());
    assertThat(review.passed()).isTrue();
    assertThat(review.errorCode()).isNull();
    verify(ai).analyseJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_V2_JUDGE_PROTOCOL_RECOVERY"));
  }

  @Test void unavailableSolverLeavesCandidatePendingInsteadOfPretendingQualityFailure() {
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_SOLVE")))
        .thenThrow(new IllegalStateException("timeout"));
    var question = question();
    var review = service.review(question);
    assertThat(review.available()).isFalse();
    assertThat(question.get("stem")).isNotNull();
    verify(ai, never()).analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_JUDGE"));
  }

  @Test void authorCanRequestResearchAfterPlanningAndReceiveActualSearchResult() {
    var draft = draft(); draft.put("_assessmentWebSearchEnabled", true);
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR")))
        .thenReturn(searchAction(), author());
    prepareSearch();
    assertThat(service.generate(List.of(draft)).getFirst()).containsKey("stem");
    verify(web).searchForAssessment("CAD 打印比例 官方文档");
    ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
    verify(ai, times(2)).analyseJson(anyString(), prompts.capture(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR"));
    assertThat(prompts.getAllValues().get(1)).contains("SEARCHED", "https://example.org/manual");
  }

  @Test void solverCanIndependentlyRequestResearchWithoutAuthorResearch() {
    var question = question(); question.put("_assessmentWebSearchEnabled", true);
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_SOLVE")))
        .thenReturn(searchAction(), solution("B"));
    prepareSearch(); judgeReturns(judgment());
    assertThat(service.review(question).passed()).isTrue();
    verify(web).searchForAssessment("CAD 打印比例 官方文档");
  }

  @Test void disabledSearchNeverCallsPublicSearchEvenWhenModelRequestsIt() {
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR")))
        .thenReturn(searchAction(), author());
    var draft = draft();
    service.generate(List.of(draft));
    verifyNoInteractions(web);
    assertThat(draft.get("_assessmentAuthorResearch").toString()).contains("DISABLED");
  }

  @Test void failuresAreExplicitAndDoNotDiscardOtherwiseSelfContainedQuestion() {
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR")))
        .thenReturn(searchAction(), author());
    prepareSearch();
    when(web.searchForAssessment(anyString())).thenThrow(new IllegalStateException("unavailable"));
    var draft = draft(); draft.put("_assessmentWebSearchEnabled", true);
    assertThat(service.generate(List.of(draft)).getFirst()).containsKey("stem");
    assertThat(draft.get("_assessmentAuthorResearch").toString()).contains("UNAVAILABLE");
  }

  @Test void sharedRunBudgetCachesQueriesWithoutCrossProjectState() {
    var budget = new OpenAssessmentService.ResearchBudget(1);
    var first = draft(); first.put("_assessmentWebSearchEnabled", true); first.put("_assessmentResearchBudget", budget);
    var second = new LinkedHashMap<>(first); second.put("sequence", 2);
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR")))
        .thenReturn(searchAction(), author(), searchAction(), author());
    prepareSearch();
    service.generate(List.of(first, second));
    verify(web, times(1)).searchForAssessment(anyString());
    assertThat(budget.used()).isEqualTo(1);
    assertThat(second.get("_assessmentAuthorResearch").toString()).contains("cached=true");
  }

  @Test void exhaustedBudgetNeverMakesAnotherPublicRequest() {
    var draft = draft(); draft.put("_assessmentWebSearchEnabled", true);
    draft.put("_assessmentResearchBudget", new OpenAssessmentService.ResearchBudget(0));
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR")))
        .thenReturn(searchAction(), author());
    prepareSearch(); service.generate(List.of(draft));
    verifyNoInteractions(web);
    assertThat(draft.get("_assessmentAuthorResearch").toString()).contains("BUDGET_EXHAUSTED");
  }

  @Test void unsafeQueryIsNotSentToTheSearchProvider() {
    var draft = draft(); draft.put("_assessmentWebSearchEnabled", true);
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR")))
        .thenReturn(searchAction(), author());
    when(ai.analyseJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_WEB_QUERY")))
        .thenReturn("{\"query\":\"user@example.org 内部图纸.pdf\"}");
    service.generate(List.of(draft));
    verifyNoInteractions(web);
    assertThat(OpenAssessmentService.safeQuery("GB/T 4457 CAD drawing")).isTrue();
  }

  @Test void failedItemDoesNotDiscardAnotherItem() {
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR")))
        .thenThrow(new IllegalStateException("unavailable")).thenReturn(author());
    var second = draft(); second.put("sequence", 2);
    var result = service.generate(List.of(draft(), second));
    assertThat(result).hasSize(2);
    assertThat(result.get(0)).containsKey("_authorFailure");
    assertThat(result.get(1)).containsKey("stem");
  }

  @Test void numericToolComputesWithoutExecutingCode() {
    assertThat(AssessmentCalculator.calculate("120 / (1 - 0.02)")).startsWith("122.4489795918");
    assertThat(AssessmentCalculator.calculate("120 * (1 + 0.02)")).isEqualTo("122.4");
    assertThat(AssessmentCalculator.calculate("-(2+3)*4")).isEqualTo("-20");
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> AssessmentCalculator.calculate("Runtime.exec(1)"));
    org.junit.jupiter.api.Assertions.assertThrows(ArithmeticException.class, () -> AssessmentCalculator.calculate("1/0"));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> AssessmentCalculator.calculate("1 2" + "a"));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> AssessmentCalculator.calculate("1 2"));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> AssessmentCalculator.calculate("1 .2"));
  }

  @Test void calculationQuestionMustHaveAnActualNumericToolCheck() {
    var question = question(); question.put("type", "CALCULATION"); question.put("answer", "40毫米");
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_SOLVE")))
        .thenReturn("{\"action\":\"CALCULATE\",\"expressions\":[\"80/2\"]}", solution("40毫米"));
    judgeReturns(judgment());
    assertThat(service.review(question).passed()).isTrue();
    assertThat(question.get("_assessmentSolveResearch").toString()).contains("CALCULATED", "value=40");
    verifyNoInteractions(web);
  }

  @Test void cannotClaimCalculationHappenedWithoutUsingTheTool() {
    var question = question(); question.put("type", "CALCULATION");
    solveReturns(solution("40毫米"));
    assertThat(service.review(question).available()).isFalse();
    verify(ai, never()).analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_JUDGE"));
  }

  @Test void proGenerationUsesTheConfiguredQuestionModelRoute() {
    when(ai.analyseQuestionJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR"))).thenReturn(author());
    assertThat(refiner().generateCandidates(List.of(draft()), "PROFESSIONAL_PRO").getFirst().valid()).isTrue();
    verify(ai).analyseQuestionJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR"));
  }

  @Test void generalTransferAuthorUsesDomainBriefInsteadOfCopyableParameterLists() {
    var draft = draft(); draft.put("_assessmentDomainBrief", "几何约束与比例推理");
    draft.put("_assessmentCorpusContext", "PRIVATE_PARAMETER_LIST_0.7_0.35");
    draft.put("_assessmentKnowledgeUse", "GENERAL"); authorReturns(author());
    service.generate(List.of(draft));
    ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
    verify(ai).analyseJson(anyString(), input.capture(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR"));
    assertThat(input.getValue()).contains("几何约束与比例推理").doesNotContain("PRIVATE_PARAMETER_LIST");
  }

  @Test void surfacePatternShortcutBlocksMediumQuestionEvenWithPassStatus() {
    solveReturns(solution("B")); judgeReturns(judgment().replace("\"surfaceShortcut\":false", "\"surfaceShortcut\":true"));
    assertThat(service.review(question()).flags()).contains("SURFACE_SHORTCUT");
  }

  @Test void excludingAValidAlternativeCannotBeRubberStampedByPassStatus() {
    solveReturns(solution("B"));
    judgeReturns(judgment().replace("\"valid\":false,\"excludedByRubric\":false", "\"valid\":true,\"excludedByRubric\":true"));
    assertThat(service.review(question()).flags()).contains("VALID_ALTERNATIVE_EXCLUDED");
  }

  @Test void emptyVisibleJudgmentHasOneNonThinkingProtocolRecovery() {
    solveReturns(solution("B"));
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_JUDGE")))
        .thenThrow(new IllegalStateException("模型响应为空或格式异常"));
    when(ai.analyseJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_V2_JUDGE_RECOVERY")))
        .thenReturn(judgment());
    assertThat(service.review(question()).passed()).isTrue();
  }

  @Test void fastUsesLowEffortButHardAuthorRetainsDeeperDesign() {
    assertThat(OpenAssessmentService.policy(draft(), "AUTHOR", 0).effort()).isEqualTo("low");
    assertThat(OpenAssessmentService.policy(draft(), "SOLVE", 0).effort()).isEqualTo("low");
    var hard = draft(); hard.put("difficulty", "HARD");
    assertThat(OpenAssessmentService.policy(hard, "AUTHOR", 0).effort()).isEqualTo("high");
    assertThat(OpenAssessmentService.policy(hard, "AUTHOR", 1).effort()).isEqualTo("low");
    assertThat(OpenAssessmentService.policy(hard, "SOLVE", 0).maxTokens()).isGreaterThanOrEqualTo(8_000);
  }

  @Test void truncatedAuthorRecoversWithoutTryingToRepairPartialJson() {
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR")))
        .thenThrow(new ModelResponseException(ModelResponseException.Reason.TRUNCATED));
    when(ai.analyseJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_V2_AUTHOR_RECOVERY")))
        .thenReturn(author());
    var draft = draft();
    assertThat(service.generate(List.of(draft)).getFirst()).containsKey("stem");
    assertThat(draft.get("_assessmentExecution").toString()).contains("TRUNCATED", "RECOVERY");
    verify(ai, never()).analyseJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_V2_FORMAT_REPAIR"));
  }

  @Test void revisedItemRemembersSuccessfulRecoveryOnlyForThatStage() {
    var question = draft();
    question.put("_assessmentExecution", List.of(Map.of("stage", "SOLVE", "recovery", true, "result", "RESPONSE_RECEIVED")));
    assertThat(OpenAssessmentService.policy(question, "SOLVE", 0)).isEqualTo(new OpenAssessmentService.CallPolicy("none", 5_000));
    assertThat(OpenAssessmentService.policy(question, "SOLVE", 1).effort()).isEqualTo("none");
    assertThat(OpenAssessmentService.policy(question, "AUTHOR", 0).effort()).isEqualTo("low");
    assertThat(OpenAssessmentService.policy(draft(), "SOLVE", 0).effort()).isEqualTo("low");
    question.put("_assessmentExecution", List.of(Map.of("stage", "SOLVE", "recovery", true, "result", "TRUNCATED")));
    assertThat(OpenAssessmentService.policy(question, "SOLVE", 0).effort()).isEqualTo("low");
  }

  @Test void reviewPromptExcludesHistoricalAuditsAndDuplicateRubric() {
    var question = question();
    question.put("_assessmentDesign", Map.of("kind", "GENERAL", "criticalClaims", List.of("长度比例知识"),
        "PriorAttempt", Map.of("answer", "HISTORICAL_ANSWER"), "ReviewRetries", List.of("OLD_REVIEW"),
        "rubricItems", List.of(Map.of("criterion", "DUPLICATE_RUBRIC", "points", 2))));
    solveReturns(solution("B")); judgeReturns(judgment());
    assertThat(service.review(question).passed()).isTrue();
    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(ai).analyseJson(anyString(), prompt.capture(), anyInt(), anyString(), eq("ASSESSMENT_V2_JUDGE"));
    assertThat(prompt.getValue()).contains("长度比例知识").doesNotContain("HISTORICAL_ANSWER", "OLD_REVIEW", "DUPLICATE_RUBRIC");
  }

  @Test void emptySolverHasBoundedRecoveryWithBlindInputStillIsolated() {
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_SOLVE")))
        .thenThrow(new ModelResponseException(ModelResponseException.Reason.EMPTY));
    when(ai.analyseJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_V2_SOLVE_RECOVERY")))
        .thenReturn(solution("B"));
    judgeReturns(judgment());
    var question = question(); question.put("analysis", "AUTHOR_SECRET");
    assertThat(service.review(question).passed()).isTrue();
    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(ai).analyseJsonFast(anyString(), prompt.capture(), anyInt(), eq("ASSESSMENT_V2_SOLVE_RECOVERY"));
    assertThat(prompt.getValue()).doesNotContain("AUTHOR_SECRET", "\"answer\":\"B\"");
  }

  @Test void recoveryFailureStopsInsteadOfRequestingAnotherAuthorRewrite() {
    when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR")))
        .thenThrow(new ModelResponseException(ModelResponseException.Reason.TRUNCATED));
    when(ai.analyseJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_V2_AUTHOR_RECOVERY")))
        .thenThrow(new ModelResponseException(ModelResponseException.Reason.TRUNCATED));
    var candidate = refiner().generateCandidates(List.of(draft()), "FAST").getFirst();
    assertThat(candidate.valid()).isFalse();
    assertThat(candidate.question()).containsEntry("_assessmentNoAutoRewrite", true);
    verify(ai, times(1)).analyseJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_V2_AUTHOR_RECOVERY"));
  }

  @Test void protocolRecoveryOfExpertAuthorPreservesQuestionModel() {
    when(ai.analyseQuestionJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR")))
        .thenThrow(new ModelResponseException(ModelResponseException.Reason.EMPTY));
    when(ai.analyseQuestionJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_V2_AUTHOR_RECOVERY")))
        .thenReturn(author());
    assertThat(refiner().generateCandidates(List.of(draft()), "PROFESSIONAL_PRO").getFirst().valid()).isTrue();
    verify(ai, never()).analyseJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_V2_AUTHOR_RECOVERY"));
  }

  @Test void inlineArithmeticNeedsNoAdditionalModelTurn() {
    var question = question(); question.put("type", "CALCULATION");
    solveReturns(solution("40毫米").replace("\"unresolvedFacts\":[]", "\"unresolvedFacts\":[],"
        + "\"calculations\":[{\"quantity\":\"纸面长度mm\",\"expression\":\"80/2\",\"expected\":\"40\",\"decimals\":0}]"));
    judgeReturns(judgment());
    assertThat(service.review(question).passed()).isTrue();
    assertThat(question.get("_assessmentSolveResearch").toString()).contains("CALCULATED", "value=40");
    verify(ai, times(1)).analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_SOLVE"));
  }

  @Test void unanswerableCalculationDoesNotForceTheSolverToInventNumericInputs() {
    var question = question(); question.put("type", "CALCULATION");
    solveReturns(solution("缺少决定数值的基准").replace("\"answerable\":true", "\"answerable\":false"));
    judgeReturns(judgment());
    var review = service.review(question);
    assertThat(review.available()).isTrue();
    assertThat(review.flags()).contains("INDEPENDENT_ANSWER_UNRESOLVED");
    verify(ai, times(1)).analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_SOLVE"));
  }

  @Test void fakeCalculatorAnswerNeverPassesAfterOneCorrection() {
    var question = question(); question.put("type", "CALCULATION");
    solveReturns(solution("41毫米").replace("\"unresolvedFacts\":[]", "\"unresolvedFacts\":[],"
        + "\"calculations\":[{\"quantity\":\"长度mm\",\"expression\":\"80/2\",\"expected\":\"41\",\"decimals\":0}]"));
    assertThat(service.review(question).passed()).isFalse();
    verify(ai, times(2)).analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_SOLVE"));
    verify(ai, never()).analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_JUDGE"));
  }

  @Test void unresolvedDatumCannotPassBecauseJudgeSaysItIsImplied() {
    var question = question(); question.put("type", "CALCULATION");
    solveReturns(solution("40毫米").replace("\"ambiguities\":[]", "\"ambiguities\":[\"长度未指定截面\"]")
        .replace("\"unresolvedFacts\":[]", "\"unresolvedFacts\":[],\"calculations\":[{\"quantity\":\"长度mm\",\"expression\":\"80/2\",\"expected\":\"40\",\"decimals\":0}]"));
    judgeReturns(judgment());
    var review = service.review(question);
    assertThat(review.flags()).contains("ISSUES_NOT_RESOLVED");
    assertThat(review.feedback()).contains("长度未指定截面");
    judgeReturns(judgment().replace("\"flags\":[]", "\"issueResolutions\":[{\"id\":\"A1\",\"status\":\"STEM_COVERS\",\"reason\":\"默认开口端\",\"stemEvidence\":\"开口截面长度为80毫米\"}],\"flags\":[]"));
    assertThat(service.review(question).flags()).contains("ISSUES_NOT_RESOLVED");
  }

  @Test void rubricTotalIsValidatedAndRenderedFromOneStructuredSource() {
    var draft = draft(); draft.put("points", 2);
    authorReturns(author().replace("\"unresolvedFacts\":[]", "\"rubricItems\":[{\"criterion\":\"正确选择\",\"points\":2}],\"unresolvedFacts\":[]"));
    var question = service.generate(List.of(draft)).getFirst();
    assertThat(question.get("scoringRubric")).asString().contains("正确选择（2分）", "总分：2分");
    authorReturns(author().replace("\"unresolvedFacts\":[]", "\"rubricItems\":[{\"criterion\":\"正确选择\",\"points\":1}],\"unresolvedFacts\":[]"));
    assertThat(service.generate(List.of(draft)).getFirst().get("_authorFailure")).asString().contains("总分不符");
    assertThat(draft.get("_assessmentNoAutoRewrite")).isEqualTo(false);
  }

  @Test void contradictoryPassPreservesTheQuestionForReviewInsteadOfRewritingIt() {
    solveReturns(solution("B"));
    judgeReturns(judgment().replace("\"flags\":[]", "\"flags\":[\"盲解解释有误，但作者答案正确，不影响通过\"]"));
    var review = service.review(question());
    assertThat(review.available()).isFalse();
    assertThat(review.passed()).isFalse();
    assertThat(review.flags()).contains("REVIEW_PROTOCOL_CONFLICT", "PROFESSIONAL_REVIEW_UNAVAILABLE");
    verify(ai, times(1)).analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_JUDGE"));
  }

  @Test void selectedStrategyAndStableToolProtocolAreInTheActualAuthorPrompt() {
    var draft = draft(); draft.put("type", "CASE_ANALYSIS"); authorReturns(author());
    service.generate(List.of(draft));
    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(ai).analyseJson(anyString(), prompt.capture(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR"));
    assertThat(prompt.getValue()).startsWith("可按需调用公开网页搜索")
        .contains("【案例分析策略】", "有依据的替代方案", "知识库提供课程/受测者背景", "calculations")
        .doesNotContain("【选择题策略】", "【计算题策略】", "120/(1-0.02)");
  }

  private void prepareSearch() {
    when(ai.analyseJsonFast(anyString(), anyString(), anyInt(), eq("ASSESSMENT_WEB_QUERY")))
        .thenReturn("{\"query\":\"CAD 打印比例 官方文档\"}");
    when(web.searchForAssessment(anyString())).thenReturn(new DeepSeekWebSearchService.SearchAnswer(
        "打印比例与视口比例应区分", List.of(new DeepSeekWebSearchService.SearchSource("官方说明", "https://example.org/manual"))));
  }
  private void authorReturns(String value) { when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_AUTHOR"))).thenReturn(value); }
  private void solveReturns(String value) { when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_SOLVE"))).thenReturn(value); }
  private void judgeReturns(String value) { when(ai.analyseJson(anyString(), anyString(), anyInt(), anyString(), eq("ASSESSMENT_V2_JUDGE"))).thenReturn(value); }
  private QuestionRefinementService refiner() {
    var refiner = new QuestionRefinementService(ai, json, true, 3, true, 3, 6000, "high", "high", "high");
    ReflectionTestUtils.setField(refiner, "openAssessment", service);
    return refiner;
  }
  private static Map<String, Object> draft() {
    var draft = new LinkedHashMap<String, Object>();
    draft.put("sequence", 1); draft.put("level", "KNOWLEDGE_BASE"); draft.put("type", "SINGLE_CHOICE");
    draft.put("difficulty", "MEDIUM"); draft.put("_assessmentPipelineVersion", OpenAssessmentService.VERSION);
    draft.put("_assessmentWebSearchEnabled", false); return draft;
  }
  private static Map<String, Object> question() {
    var question = draft(); question.put("stem", "模型长度为80毫米，按1:2比例输出，纸上长度是多少？");
    question.put("options", "A.20毫米 | B.40毫米 | C.80毫米 | D.160毫米"); question.put("answer", "B"); return question;
  }
  private static String searchAction() { return "{\"action\":\"SEARCH\",\"query\":\"CAD打印比例\",\"reason\":\"核验\"}"; }
  private static String solution(String answer) { return "{\"action\":\"FINAL\",\"answer\":\"" + answer + "\",\"answerable\":true,\"solution\":\"计算80/2\",\"ambiguities\":[],\"unresolvedFacts\":[]}"; }
  private static String author() {
    return """
        {"action":"FINAL","question":{"sequence":1,"stem":"将80毫米长度的图形以1:2比例打印，纸面长度应为多少？",
        "options":{"A":"20毫米","B":"40毫米","C":"80毫米，无需缩放","D":"160毫米"},"answer":"B",
        "analysis":"按长度比例80/2得到40毫米。","scoringRubric":"正确选择得2分。",
        "basis":{"kind":"GENERAL","criticalClaims":["长度比例为1:2"],"performanceEvidence":"正确进行比例换算","unresolvedFacts":[]}}}
        """;
  }
  private static String judgment() {
    return """
        {"action":"FINAL","status":"PASS","checks":{"correctness":"PASS","answerability":"PASS",
        "alignment":"PASS","rubric":"PASS","diversity":"PASS","criticalFacts":"PASS","independentAgreement":"PASS"},
        "optionChecks":{"A":"PASS","B":"PASS","C":"PASS","D":"PASS"},"flags":[],"feedback":"",
        "counterexampleCheck":{"alternative":"其他比例不符合题设，未发现成立的反例","valid":false,"excludedByRubric":false},
        "discriminationScore":80,"outsiderSolvableScore":20,"surfaceShortcut":false,"shortcutReason":"需比例知识"}
        """;
  }
}
