package com.tikuzhushou.question;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AssessmentOutputChecksTests {
  @Test void verifiesRoundingWithoutArbitraryTolerance() {
    var result = AssessmentOutputChecks.calculations(List.of(Map.of("quantity", "型腔mm", "expression", "120/0.98", "expected", "122.45", "decimals", 2)), "SOLVE");
    assertThat(result.get("status")).isEqualTo("CALCULATED");
    assertThat(AssessmentOutputChecks.calculations(List.of(Map.of("quantity", "型腔mm", "expression", "120/0.98", "expected", "122.40", "decimals", 2)), "SOLVE").get("status"))
        .isEqualTo("CALCULATION_MISMATCH");
  }

  @Test void rejectsCodeUnitsMissingQuantityAndInvalidPrecision() {
    for (String expression : List.of("1/0", "1mm+2mm", "Runtime.exec(1)"))
      assertThat(AssessmentOutputChecks.calculations(List.of(Map.of("quantity", "长度", "expression", expression, "expected", "1", "decimals", 2)), "SOLVE").get("status"))
          .isEqualTo("CALCULATION_ERROR");
    assertThat(AssessmentOutputChecks.calculations(List.of(Map.of("expression", "1+1", "expected", "2", "decimals", 2.5)), "SOLVE").get("status"))
        .isEqualTo("CALCULATION_ERROR");
  }

  @Test void rubricPointsMustBePositiveAndAddExactly() {
    assertThat(AssessmentOutputChecks.rubricMatches(List.of(Map.of("criterion", "方法", "points", 1.5), Map.of("criterion", "结果", "points", .5)), 2)).isTrue();
    assertThat(AssessmentOutputChecks.rubricMatches(List.of(Map.of("criterion", "方法", "points", -1), Map.of("criterion", "结果", "points", 3)), 2)).isFalse();
    assertThat(AssessmentOutputChecks.rubricMatches(List.of(), 2)).isFalse();
  }

  @Test void permitsRealRubricAlternativesButNotFabricatedQuotesOrMissingIssues() {
    var issues = List.of(Map.of("id", "A1", "issue", "可选另一方法"));
    var question = Map.<String,Object>of("stem", "提出一个可行方案", "scoringRubric", "两种可行方案均可得分");
    var good = Map.of("id", "A1", "status", "RUBRIC_ACCEPTS", "reason", "评分已包含替代方案", "rubricEvidence", "两种可行方案均可得分");
    assertThat(AssessmentOutputChecks.unresolved(issues, List.of(good), question)).isEmpty();
    assertThat(AssessmentOutputChecks.unresolved(issues, List.of(good,good), question)).isNotEmpty();
    assertThat(AssessmentOutputChecks.unresolved(issues, List.of(), question)).isNotEmpty();
    assertThat(AssessmentOutputChecks.unresolved(issues, List.of(Map.of("id", "A1", "status", "STEM_COVERS", "reason", "默认知道", "stemEvidence", "未提供的假设内容")), question)).isNotEmpty();
  }

  @Test void acceptsSeparateVerbatimFragmentsWithoutAcceptingInventedParts() {
    var issues = List.of(Map.of("id", "A1", "issue", "基准与方向"));
    var question = Map.<String,Object>of("stem", "以分型面为基准。其他背景。沿开模方向测量深度。", "scoringRubric", "解释基准得分");
    var good = Map.of("id", "A1", "status", "STEM_COVERS", "reason", "题面分别明示两者", "stemEvidence", List.of("以分型面为基准", "沿开模方向测量深度"));
    assertThat(AssessmentOutputChecks.unresolved(issues, List.of(good), question)).isEmpty();
    var fabricated = Map.of("id", "A1", "status", "STEM_COVERS", "reason", "不可补造方向", "stemEvidence", List.of("以分型面为基准", "底部长度一定较小"));
    assertThat(AssessmentOutputChecks.unresolved(issues, List.of(fabricated), question)).isNotEmpty();
  }
}
