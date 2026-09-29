package com.tikuzhushou.question;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.assertj.core.api.Assertions.assertThat;

/** Executes production gates, exports actual outcomes for offline Promptfoo replay. No AI mocks/calls. */
class AssessmentRegressionFixtureTests {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<Map<String, Object>> RESULTS = new ArrayList<>();

  @TestFactory List<DynamicTest> fixedRegressions() throws Exception {
    Map<String, Object> suite;
    try (var stream = getClass().getResourceAsStream("/assessment/review-regressions.json")) {
      suite = JSON.readValue(stream, new TypeReference<>() { });
    }
    var base = map(suite.get("base"));
    List<DynamicTest> tests = new ArrayList<>();
    for (Object raw : (List<?>) suite.get("cases")) {
      var fixture = map(raw);
      tests.add(DynamicTest.dynamicTest(fixture.get("id").toString(), () -> {
        var question = merge(map(base.get("question")), map(fixture.get("question")));
        var solution = merge(map(base.get("solution")), map(fixture.get("solution")));
        var judgment = merge(map(base.get("judgment")), map(fixture.get("judgment")));
        var review = AssessmentReviewGate.evaluate(question, solution, judgment);
        var expected = map(fixture.get("expected"));
        var actual = Map.<String,Object>of("available", review.available(), "passed", review.passed(), "flags", review.flags());
        record(fixture.get("id").toString(), "review-contract", actual, expected);
        assertThat(review.available()).isEqualTo(expected.get("available"));
        assertThat(review.passed()).isEqualTo(expected.get("passed"));
        for (Object flag : (List<?>) expected.get("requiredFlags")) assertThat(review.flags()).contains(flag.toString());
      }));
    }
    for (String type : List.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "FILL_BLANK", "SHORT_ANSWER",
        "CALCULATION", "ESSAY", "CASE_ANALYSIS", "COMPREHENSIVE", "PRACTICAL_TASK")) {
      tests.add(DynamicTest.dynamicTest("strategy-" + type, () -> {
        String prompt = AssessmentAuthorStrategy.forType(type);
        boolean scoped = prompt.contains("策略】") && prompt.indexOf("策略】") == prompt.lastIndexOf("策略】");
        record("strategy-" + type, "scoped-context", Map.of("scoped", scoped), Map.of("scoped", true));
        assertThat(scoped).isTrue();
        assertThat(prompt.length()).isLessThan(400); // Character budget, explicitly NOT a token claim.
      }));
    }
    for (var test : List.of(
        List.of("rounding-cad-intermediate", "60*0.03492076949", "2.09", "CALCULATION_MISMATCH"),
        List.of("rounding-cad-correct", "60*0.03492076949", "2.10", "CALCULATED"),
        List.of("arithmetic-price", "80*0.9", "72.00", "CALCULATED"),
        List.of("no-code-execution", "Runtime.exec(1)", "1", "CALCULATION_ERROR"))) {
      tests.add(DynamicTest.dynamicTest(test.getFirst(), () -> {
        var result = AssessmentOutputChecks.calculations(List.of(Map.of("quantity", "模拟结果", "expression", test.get(1),
            "expected", test.get(2), "decimals", 2)), "SOLVE");
        record(test.getFirst(), "arithmetic", Map.of("status", result.get("status")), Map.of("status", test.get(3)));
        assertThat(result.get("status")).isEqualTo(test.get(3));
      }));
    }
    return tests;
  }

  private static synchronized void record(String id, String dimension, Map<String, Object> actual, Map<String, Object> expected) {
    RESULTS.add(Map.of("id", id, "dimension", dimension, "actual", actual, "expected", expected));
  }

  @AfterAll static void exportActualResults() throws Exception {
    Path target = Path.of("target/question-quality-eval.json");
    Files.createDirectories(target.getParent());
    JSON.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), Map.of("schemaVersion", 1,
        "executionVersion", OpenAssessmentService.EXECUTION_VERSION, "kind", "engineering-regression-not-teacher-gold",
        "modelCalls", 0, "modelTokens", 0, "rows", RESULTS));
  }

  private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay) {
    var result = new LinkedHashMap<>(base);
    overlay.forEach((key, value) -> result.put(key, value instanceof Map<?, ?> ? merge(map(base.get(key)), map(value)) : value));
    return result;
  }
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> ? JSON.convertValue(value, new TypeReference<>() { }) : Map.of();
  }
}
