package com.tikuzhushou.question;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Distinguish a defective review response from a demonstrated defect in the question. */
final class AssessmentReviewGate {
  private static final Set<String> CHOICE = Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE");
  private static final Set<String> CLOSED = Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE");
  private AssessmentReviewGate() { }

  static QuestionProfessionalReviewService.Review evaluate(Map<String, Object> question,
      Map<String, Object> solution, Map<String, Object> judgment) {
    List<String> defects = new ArrayList<>(), protocol = new ArrayList<>();
    var issues = AssessmentOutputChecks.issues(solution, map(question.get("_assessmentDesign")));
    var unresolved = AssessmentOutputChecks.unresolved(issues, judgment.get("issueResolutions"), question);
    if (!unresolved.isEmpty()) {
      boolean needsChange = judgment.get("issueResolutions") instanceof List<?> resolutions
          && resolutions.stream().map(AssessmentReviewGate::map).anyMatch(r ->
              "NEEDS_CHANGE".equals(r.get("status")) && !text(r.get("reason")).isBlank()
              && issues.stream().anyMatch(i -> i.get("id").equals(r.get("id"))));
      (needsChange ? defects : protocol).add("ISSUES_NOT_RESOLVED");
    }
    // A numeric model/condition cannot be made well-defined by widening the marking tolerance.
    // Deliberately conditional numerical tasks can still use ASKED_UNCERTAINTY, grounded in both texts.
    if ("CALCULATION".equals(question.get("type")) && !issues.isEmpty()
        && judgment.get("issueResolutions") instanceof List<?> resolutions
        && resolutions.stream().map(AssessmentReviewGate::map).anyMatch(r ->
            "RUBRIC_ACCEPTS".equals(r.get("status")) && issues.stream().anyMatch(i -> i.get("id").equals(r.get("id"))))) {
      protocol.add("NUMERIC_ASSUMPTIONS_REQUIRE_STEM_EVIDENCE");
    }
    var counterexample = map(judgment.get("counterexampleCheck"));
    if (!(counterexample.get("valid") instanceof Boolean)
        || !(counterexample.get("excludedByRubric") instanceof Boolean)
        || text(counterexample.get("alternative")).isBlank()) protocol.add("COUNTEREXAMPLE_CHECK_MISSING");
    else if (Boolean.TRUE.equals(counterexample.get("valid")) && Boolean.TRUE.equals(counterexample.get("excludedByRubric")))
      defects.add("VALID_ALTERNATIVE_EXCLUDED");
    if (!(judgment.get("surfaceShortcut") instanceof Boolean)) protocol.add("SURFACE_CHALLENGE_MISSING");
    else if (Boolean.TRUE.equals(judgment.get("surfaceShortcut")) && !"EASY".equals(question.get("difficulty")))
      defects.add("SURFACE_SHORTCUT");
    var checks = map(judgment.get("checks"));
    for (String check : List.of("correctness", "answerability", "alignment", "rubric", "diversity", "criticalFacts", "independentAgreement")) {
      if ("FAIL".equals(checks.get(check))) defects.add("ASSESSMENT_" + check.toUpperCase(Locale.ROOT) + "_FAILED");
      else if (!"PASS".equals(checks.get(check))) protocol.add("ASSESSMENT_" + check.toUpperCase(Locale.ROOT) + "_MISSING");
    }
    if (Boolean.FALSE.equals(solution.get("answerable"))) defects.add("INDEPENDENT_ANSWER_UNRESOLVED");
    else if (!Boolean.TRUE.equals(solution.get("answerable"))) protocol.add("INDEPENDENT_SOLUTION_INCOMPLETE");
    if (CLOSED.contains(text(question.get("type")))) {
      if (solution.get("ambiguities") instanceof List<?> list && !list.isEmpty()) defects.add("INDEPENDENT_AMBIGUITY");
      if (!canonicalAnswer(question.get("answer")).equals(canonicalAnswer(solution.get("answer"))))
        defects.add("INDEPENDENT_ANSWER_MISMATCH");
    }
    List<String> modelFlags = new ArrayList<>();
    if (judgment.get("flags") instanceof List<?> list) {
      for (Object flag : list) {
        if (!(flag instanceof String)) protocol.add("REVIEW_FLAGS_INVALID");
        else if (!text(flag).isBlank()) modelFlags.add(text(flag));
      }
    } else protocol.add("REVIEW_FLAGS_MISSING");
    List<QuestionProfessionalReviewService.OptionReview> options = new ArrayList<>();
    if (CHOICE.contains(text(question.get("type")))) {
      var optionChecks = map(judgment.get("optionChecks"));
      for (String option : List.of("A", "B", "C", "D")) {
        boolean pass = "PASS".equals(optionChecks.get(option));
        options.add(new QuestionProfessionalReviewService.OptionReview(option, pass, List.of(), "学科误解审查", ""));
        if ("FAIL".equals(optionChecks.get(option))) defects.add("OPTION_" + option + "_FAILED");
        else if (!pass) protocol.add("OPTION_" + option + "_MISSING");
      }
    }
    String status = text(judgment.get("status"));
    if (!Set.of("PASS", "REWRITE").contains(status)) protocol.add("REVIEW_STATUS_INVALID");
    // Free-text flags alone cannot prove a defect when all structured checks say PASS.
    if (("PASS".equals(status) && !modelFlags.isEmpty()) || ("REWRITE".equals(status) && defects.isEmpty()))
      protocol.add("REVIEW_PROTOCOL_CONFLICT");
    boolean available = !defects.isEmpty() || protocol.isEmpty();
    List<String> flags = new ArrayList<>(defects);
    flags.addAll(protocol); flags.addAll(modelFlags);
    if (!available) flags.add("PROFESSIONAL_REVIEW_UNAVAILABLE");
    boolean passed = available && "PASS".equals(status) && flags.isEmpty();
    String feedback = text(judgment.get("feedback"));
    if (!unresolved.isEmpty()) feedback += " 未闭合异议：" + String.join("；", unresolved);
    if (!available) feedback = "审查响应不完整或结论矛盾，保留原题待复核，不自动重写。 " + feedback;
    if (!passed) feedback = compact(feedback, 650) + " 独立作答摘要：" + compact(text(solution.get("answer")), 220)
        + "；检查项：" + String.join(",", flags);
    return new QuestionProfessionalReviewService.Review(number(question.get("sequence")), passed,
        score(judgment.get("discriminationScore")), score(judgment.get("outsiderSolvableScore")),
        List.copyOf(flags), feedback.trim(), List.copyOf(options), available);
  }

  private static String canonicalAnswer(Object value) {
    String answer = text(value).toUpperCase(Locale.ROOT).replaceAll("[\\s、,，]", "");
    if (Set.of("对", "是", "TRUE", "√").contains(answer)) return "正确";
    if (Set.of("错", "否", "FALSE", "×").contains(answer)) return "错误";
    return answer.chars().sorted().collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
  }
  private static String text(Object value) { return Objects.toString(value, "").trim(); }
  private static int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
  private static int score(Object value) { return Math.max(0, Math.min(100, number(value))); }
  private static String compact(String value, int length) { return value.length() <= length ? value : value.substring(0, length) + "…"; }
  private static Map<String, Object> map(Object raw) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (raw instanceof Map<?, ?> entries) entries.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }
}
