package com.tikuzhushou.project;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class QuestionQualityChecksTests {
  Map<String,Object> question(){return new LinkedHashMap<>(Map.of("stem","请解释此时应如何选择合适的模型。","answer","取决于边界条件","analysis","对照边界条件判断","scoringRubric","条件与结论各一分"));}
  @Test void exactDecimalRubricSum(){var q=question();q.put("scoringItems",List.of(Map.of("criterion","识别条件","points","0.10"),Map.of("criterion","给出结论","points","1.90")));assertThat(QuestionQualityChecks.inspect(q,"SHORT_ANSWER",2)).isEmpty();}
  @Test void incorrectRubricSumBlocks(){var q=question();q.put("scoringItems",List.of(Map.of("criterion","解释","points",3)));assertThat(QuestionQualityChecks.inspect(q,"SHORT_ANSWER",5)).extracting(QuestionQualityChecks.Issue::code).contains("RUBRIC_TOTAL");}
  @Test void rubricNeedsPositiveScoresAndDescriptions(){var q=question();q.put("scoringItems",List.of(Map.of("criterion","","points",0)));assertThat(QuestionQualityChecks.inspect(q,"SHORT_ANSWER",5)).extracting(QuestionQualityChecks.Issue::code).contains("RUBRIC_ITEM");}
  @Test void legacyTextIsWarningNotFabricatedNumericValidation(){assertThat(QuestionQualityChecks.inspect(question(),"SHORT_ANSWER",2)).extracting(QuestionQualityChecks.Issue::code).containsExactly("RUBRIC_TEXT");}
  @Test void duplicateOptionsAndAnswersDetected(){var q=question();q.put("options","A. 甲 | B. 甲 | C. 丙 | D. 丁");q.put("answer","A、A");assertThat(QuestionQualityChecks.inspect(q,"MULTIPLE_CHOICE",2)).extracting(QuestionQualityChecks.Issue::code).contains("DUPLICATE_OPTIONS","ANSWER_FORMAT");}
  @Test void shortAndGeneralizedQuestionsDoNotRequireSources(){var q=question();q.put("stem","1+1=?");q.put("answer","A");q.put("options",Map.of("A","2","B","3","C","4","D","5"));assertThat(QuestionQualityChecks.inspect(q,"SINGLE_CHOICE",2)).isEmpty();}
  @Test void similarityDoesNotConflateEmptyQuestions(){assertThat(QuestionQualityChecks.similarity("","")).isZero();assertThat(QuestionQualityChecks.similarity("题干，条件。","题干 条件")).isEqualTo(1);assertThat(QuestionQualityChecks.similarity("投影方向","化学反应")).isZero();}
}
