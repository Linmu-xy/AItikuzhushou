package com.tikuzhushou.question;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.ai.DeepSeekService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenKnowledgeAssessmentTests {
  @Test
  void authorSeesWholeCorpusAndMayUseGeneralKnowledgeWithoutCopyingAnExcerpt() {
    DeepSeekService ai = mock(DeepSeekService.class);
    when(ai.analyseJson(anyString(), anyString(), eq(7000), eq("high"), eq("QUESTION_KB_OPEN")))
        .thenReturn(response("某图纸交付前需同时检查打印粗细与标注可读性，哪项调整顺序更合适？"));
    QuestionRefinementService service = service(ai);

    QuestionRefinementService.Candidate candidate = service.generateCandidates(List.of(draft()), "FAST").getFirst();

    assertThat(candidate.valid()).isTrue();
    assertThat(candidate.question().get("_assessmentDesign")).isInstanceOf(Map.class);
    assertThat(service.deliveryQuestion(candidate.question())).doesNotContainKey("_assessmentCorpusContext");
    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(ai).analyseJson(anyString(), prompt.capture(), eq(7000), eq("high"), eq("QUESTION_KB_OPEN"));
    assertThat(prompt.getValue()).contains("整库：CAD图层与标注", "可运用稳定的学科知识");
    assertThat(prompt.getValue()).doesNotContain("答案所需专业事实必须有证据");
  }

  @Test
  void rejectsNewQuestionThatAssumesAnUnprovidedDwg() {
    DeepSeekService ai = mock(DeepSeekService.class);
    when(ai.analyseJson(anyString(), anyString(), eq(7000), eq("high"), eq("QUESTION_KB_OPEN")))
        .thenReturn(response("请修复给定DWG文件中的线宽错误，并提交修正后的图纸。"));
    Map<String, Object> input = draft();
    input.put("_assessmentCorpusContext", "资料文字提到练习.dwg，但项目只上传了PDF。");

    QuestionRefinementService.Candidate candidate = service(ai).generateCandidates(List.of(input), "FAST").getFirst();

    assertThat(candidate.valid()).isFalse();
    assertThat(candidate.failureReason()).contains("未提供的源文件");
  }

  private static QuestionRefinementService service(DeepSeekService ai) {
    return new QuestionRefinementService(ai, new ObjectMapper(), true, 3, true, 3,
        6000, "high", "high", "high");
  }

  private static Map<String, Object> draft() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("sequence", 1);
    value.put("type", "SINGLE_CHOICE");
    value.put("difficulty", "MEDIUM");
    value.put("level", "KNOWLEDGE_BASE");
    value.put("assessmentPoint", "交付检查决策");
    value.put("assessmentTask", "诊断打印与标注问题");
    value.put("sourceRef", "第1页");
    value.put("sourceExcerpt", "图层粗实线线宽为0.7毫米，细实线线宽为0.35毫米。");
    value.put("_assessmentPipelineVersion", "OPEN_ASSESSMENT_V1");
    value.put("_assessmentCorpusContext", "整库：CAD图层与标注。原图包含零件图。");
    value.put("_assessmentAnswerability", "考生能根据题干条件选择检查路径");
    value.put("_assessmentKnowledgeUse", "GENERAL");
    return value;
  }

  private static String response(String stem) {
    return "{\"questions\":[{\"sequence\":1,\"stem\":\"" + stem + "\","
        + "\"options\":{\"A\":\"先核对打印设置，再检查局部标注空间\","
        + "\"B\":\"先调整标注空间，再核对打印设置\","
        + "\"C\":\"先修改图层参数，再检查局部标注空间\","
        + "\"D\":\"先检查图纸比例，再修改图层参数\"},"
        + "\"answer\":\"A\",\"analysis\":\"先核对交付设置，避免把显示问题误当为图层错误。\","
        + "\"scoringRubric\":\"选择正确检查顺序得2分，其他选择得0分。\","
        + "\"basis\":{\"kind\":\"GENERAL\",\"criticalClaims\":[\"先核对交付设置\"]}}]}";
  }
}
