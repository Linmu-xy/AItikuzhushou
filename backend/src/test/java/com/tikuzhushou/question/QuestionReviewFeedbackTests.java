package com.tikuzhushou.question;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.tikuzhushou.ai.DeepSeekService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionReviewFeedbackTests {
  @Test
  void knowledgeBaseReviewUsesVisibleJsonWithoutReasoningBudget() {
    DeepSeekService ai = mock(DeepSeekService.class);
    QuestionProfessionalReviewService service = new QuestionProfessionalReviewService(
        ai, new ObjectMapper(), null, true, 75, 5, 35, "ENFORCE");
    when(ai.analyseJsonFast(anyString(), anyString(), eq(4096), eq("QUESTION_REVIEW_KB")))
        .thenReturn("{\"reviews\":[{\"sequence\":1,\"status\":\"PASS\",\"discriminationScore\":85,\"outsiderSolvableScore\":10,\"flags\":[],\"feedback\":\"\",\"optionChecks\":{\"A\":\"PASS\",\"B\":\"PASS\",\"C\":\"PASS\",\"D\":\"PASS\"}}]}");

    QuestionProfessionalReviewService.Review result = service.review(List.of(Map.of(
        "sequence", 1, "level", "KNOWLEDGE_BASE", "type", "SINGLE_CHOICE",
        "stem", "图层设置应如何选择？", "answer", "A", "sourceExcerpt", "图层设置规范。"))).getFirst();

    assertTrue(result.available());
    assertTrue(result.passed());
    verify(ai).analyseJsonFast(anyString(), anyString(), eq(4096), eq("QUESTION_REVIEW_KB"));
  }

  @Test
  void openAssessmentReviewDoesNotTreatOneExcerptAsTheKnowledgeLimit() {
    DeepSeekService ai = mock(DeepSeekService.class);
    QuestionProfessionalReviewService service = new QuestionProfessionalReviewService(
        ai, new ObjectMapper(), null, true, 75, 5, 35, "ENFORCE");
    when(ai.analyseJsonFast(anyString(), anyString(), eq(4096), eq("QUESTION_REVIEW_KB_OPEN")))
        .thenReturn("{\"reviews\":[{\"sequence\":1,\"status\":\"PASS\",\"discriminationScore\":85,\"outsiderSolvableScore\":10,\"flags\":[],\"feedback\":\"\",\"optionChecks\":{\"A\":\"PASS\",\"B\":\"PASS\",\"C\":\"PASS\",\"D\":\"PASS\"}}]}");
    java.util.Map<String, Object> question = new java.util.LinkedHashMap<>();
    question.put("sequence", 1);
    question.put("level", "KNOWLEDGE_BASE");
    question.put("type", "SINGLE_CHOICE");
    question.put("stem", "交付前应怎样检查打印与标注设置？");
    question.put("answer", "A");
    question.put("sourceExcerpt", "样卷列出图层参数。");
    question.put("_assessmentPipelineVersion", "OPEN_ASSESSMENT_V1");
    question.put("_assessmentCorpusContext", "整库材料：图层、标注及零件图。");
    question.put("_assessmentDesign", Map.of("kind", "GENERAL", "criticalClaims", List.of("交付检查顺序")));

    QuestionProfessionalReviewService.Review result = service.review(List.of(question)).getFirst();

    assertTrue(result.passed());
    org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(ai).analyseJsonFast(anyString(), prompt.capture(), eq(4096), eq("QUESTION_REVIEW_KB_OPEN"));
    assertTrue(prompt.getValue().contains("不是通用知识的上限"));
    assertTrue(prompt.getValue().contains("整库材料：图层"));
    assertTrue(prompt.getValue().contains("ANSWER_IN_STEM"));
    assertTrue(prompt.getValue().contains("VISUAL_CLAIM_WITHOUT_IMAGE"));
  }

  @Test
  void blocksLeakedAnswerEvenWhenModelReportsPass() throws Exception {
    QuestionProfessionalReviewService service = new QuestionProfessionalReviewService(
        null, new ObjectMapper(), null, true, 75, 5, 35, "ENFORCE");
    Method parse = QuestionProfessionalReviewService.class.getDeclaredMethod("parseReview", Map.class, Map.class);
    parse.setAccessible(true);
    Map<String, Object> review = Map.of("sequence", 1, "status", "PASS", "discriminationScore", 90,
        "outsiderSolvableScore", 10, "flags", List.of("ANSWER_IN_STEM"),
        "optionChecks", Map.of("A", "PASS", "B", "PASS", "C", "PASS", "D", "PASS"), "feedback", "题干泄露答案");

    QuestionProfessionalReviewService.Review result = (QuestionProfessionalReviewService.Review) parse.invoke(
        service, review, Map.of("type", "SINGLE_CHOICE"));

    assertFalse(result.passed());
    assertTrue(result.flags().contains("ANSWER_IN_STEM"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void tellsOpenReviewerWhenOriginalImageIsAttached() throws Exception {
    QuestionProfessionalReviewService service = new QuestionProfessionalReviewService(
        null, new ObjectMapper(), null, true, 75, 5, 35, "ENFORCE");
    Method input = QuestionProfessionalReviewService.class.getDeclaredMethod("input", Map.class);
    input.setAccessible(true);
    Map<String, Object> question = new java.util.LinkedHashMap<>();
    question.put("_assessmentPipelineVersion", "OPEN_ASSESSMENT_V1");
    question.put("stimuli", List.of(Map.of("documentId", java.util.UUID.randomUUID(), "page", 1)));

    Map<String, Object> reviewed = (Map<String, Object>) input.invoke(service, question);

    assertEquals(true, reviewed.get("stimuliAttached"));
  }

  @Test
  void passingReviewDoesNotDisplayFailureFeedback() throws Exception {
    QuestionProfessionalReviewService service = new QuestionProfessionalReviewService(
        null, new ObjectMapper(), null, true, 75, 5, 35, "ENFORCE");
    Method parse = QuestionProfessionalReviewService.class.getDeclaredMethod("parseReview", Map.class, Map.class);
    parse.setAccessible(true);
    Map<String, Object> review = Map.of("sequence", 1, "status", "PASS", "discriminationScore", 85,
        "outsiderSolvableScore", 10, "flags", List.of(),
        "optionChecks", Map.of("A", "PASS", "B", "PASS", "C", "PASS", "D", "PASS"), "feedback", "");

    QuestionProfessionalReviewService.Review result = (QuestionProfessionalReviewService.Review) parse.invoke(
        service, review, Map.of("type", "SINGLE_CHOICE"));

    assertTrue(result.passed());
    assertEquals("", result.feedback());
  }

  @Test
  void failedReviewKeepsActionableFallback() throws Exception {
    QuestionProfessionalReviewService service = new QuestionProfessionalReviewService(
        null, new ObjectMapper(), null, true, 75, 5, 35, "ENFORCE");
    Method parse = QuestionProfessionalReviewService.class.getDeclaredMethod("parseReview", Map.class, Map.class);
    parse.setAccessible(true);
    Map<String, Object> review = Map.of("sequence", 1, "status", "REWRITE", "discriminationScore", 40,
        "outsiderSolvableScore", 70, "flags", List.of("AMBIGUOUS"),
        "optionChecks", Map.of("A", "PASS", "B", "PASS", "C", "PASS", "D", "PASS"), "feedback", "");

    QuestionProfessionalReviewService.Review result = (QuestionProfessionalReviewService.Review) parse.invoke(
        service, review, Map.of("type", "SINGLE_CHOICE"));

    assertFalse(result.passed());
    assertEquals("专业审题未通过", result.feedback());
  }
}
