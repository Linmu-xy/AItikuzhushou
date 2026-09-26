package com.tikuzhushou.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeQuestionDeliveryTests {
  @Test
  void hidesFileNameInDeliveredKnowledgeBaseQuestion() {
    QuestionRefinementService service = new QuestionRefinementService(
        null, null, true, 3, true, 3, 6000, "high", "high", "high");
    Map<String, Object> delivered = service.deliveryQuestion(Map.of(
        "level", "KNOWLEDGE_BASE",
        "stem", "《模拟试卷A.pdf》中的中心线应如何设置？",
        "analysis", "根据《模拟试卷A.pdf》中的图层要求选择红色。",
        "scoringRubric", "参照《模拟试卷A.pdf》评分。"));

    assertThat(delivered.get("stem")).isEqualTo("资料中的中心线应如何设置？");
    assertThat(delivered.get("analysis")).isEqualTo("根据资料中的图层要求选择红色。");
    assertThat(delivered.get("scoringRubric")).isEqualTo("参照资料评分。");
  }

  @Test
  void leavesNonKnowledgeBaseQuestionUntouched() {
    QuestionRefinementService service = new QuestionRefinementService(
        null, null, true, 3, true, 3, 6000, "high", "high", "high");
    Map<String, Object> delivered = service.deliveryQuestion(Map.of(
        "level", "CAREER", "analysis", "阅读《专业标准.pdf》"));

    assertThat(delivered.get("analysis")).isEqualTo("阅读《专业标准.pdf》");
  }
}
