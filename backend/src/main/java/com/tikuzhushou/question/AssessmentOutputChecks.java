package com.tikuzhushou.question;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic checks, never a substitute for checking the domain assumptions behind a formula. */
final class AssessmentOutputChecks {
  private AssessmentOutputChecks() { }

  static Map<String, Object> calculations(Object raw, String stage) {
    List<Map<String, Object>> results = new ArrayList<>();
    try {
      if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 6)
        throw new IllegalArgumentException("需提供1至6项计算");
      boolean allMatch = true;
      for (Object entry : list) {
        if (!(entry instanceof Map<?, ?> value)) throw new IllegalArgumentException("计算项不是对象");
        String expression = text(value.get("expression")), expected = text(value.get("expected"));
        String quantity = text(value.get("quantity"));
        Object decimalsValue = value.get("decimals");
        if (quantity.isBlank() || quantity.length() > 180 || expected.length() > 80
            || !expected.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
            || !(decimalsValue instanceof Number decimals) || decimals.doubleValue() != decimals.intValue()
            || decimals.intValue() < 0 || decimals.intValue() > 8)
          throw new IllegalArgumentException("需说明计算对象、数值与0至8位小数的舍入精度");
        String actual = AssessmentCalculator.calculate(expression);
        int places = ((Number) decimalsValue).intValue();
        BigDecimal rounded = new BigDecimal(actual).setScale(places, RoundingMode.HALF_UP);
        boolean match = rounded.compareTo(new BigDecimal(expected)) == 0;
        allMatch &= match;
        results.add(Map.of("quantity", quantity, "expression", expression, "value", actual,
            "expected", expected, "decimals", places, "matches", match));
      }
      return Map.of("stage", stage, "status", allMatch ? "CALCULATED" : "CALCULATION_MISMATCH", "results", results);
    } catch (RuntimeException invalid) {
      return Map.of("stage", stage, "status", "CALCULATION_ERROR",
          "message", "只允许纯数字四则算式；需提供quantity、expected和整数decimals（0至8），禁止代码/变量/单位进入算式");
    }
  }

  static boolean rubricMatches(Object raw, Object points) {
    if (!(points instanceof Number number) || number.doubleValue() <= 0) return true;
    if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 30) return false;
    BigDecimal total = BigDecimal.ZERO;
    try {
      for (Object entry : list) {
        if (!(entry instanceof Map<?, ?> item) || text(item.get("criterion")).isBlank()) return false;
        BigDecimal credit = new BigDecimal(text(item.get("points")));
        if (credit.signum() <= 0) return false;
        total = total.add(credit);
      }
      return total.compareTo(new BigDecimal(points.toString())) == 0;
    } catch (RuntimeException invalid) { return false; }
  }

  static List<Map<String, String>> issues(Map<String, Object> solution, Map<String, Object> design) {
    List<Map<String, String>> issues = new ArrayList<>();
    append(issues, "A", solution.get("ambiguities"));
    append(issues, "U", solution.get("unresolvedFacts"));
    append(issues, "F", design.get("unresolvedFacts"));
    return List.copyOf(issues);
  }

  private static void append(List<Map<String, String>> issues, String prefix, Object raw) {
    if (raw instanceof List<?> list) for (int i = 0; i < list.size(); i++) {
      String content = text(list.get(i));
      if (!content.isBlank()) issues.add(Map.of("id", prefix + (i + 1), "issue", content));
    }
  }

  /** Every issue needs a disposition anchored in real stem/rubric text; invented evidence cannot close it. */
  static List<String> unresolved(List<Map<String, String>> issues, Object raw,
      Map<String, Object> question) {
    if (issues.isEmpty()) return List.of();
    Map<String, Map<?, ?>> resolutions = new LinkedHashMap<>();
    List<String> failures = new ArrayList<>();
    if (raw instanceof List<?> list) for (Object entry : list) {
      if (!(entry instanceof Map<?, ?> value)) { failures.add("无效异议处理项"); continue; }
      String id = text(value.get("id"));
      if (resolutions.putIfAbsent(id, value) != null || issues.stream().noneMatch(issue -> issue.get("id").equals(id)))
        failures.add("重复或未知异议编号：" + id);
    }
    for (Map<String, String> issue : issues) {
      Map<?, ?> resolution = resolutions.get(issue.get("id"));
      boolean closed = false;
      if (resolution != null && !text(resolution.get("reason")).isBlank()) {
        boolean inStem = quoted(question.get("stem"), resolution.get("stemEvidence"));
        boolean inRubric = quoted(question.get("scoringRubric"), resolution.get("rubricEvidence"));
        closed = switch (text(resolution.get("status"))) {
          case "STEM_COVERS" -> inStem;
          case "RUBRIC_ACCEPTS" -> inRubric;
          case "ASKED_UNCERTAINTY" -> inStem && inRubric;
          default -> false;
        };
      }
      if (!closed) failures.add(issue.get("id") + "：" + issue.get("issue"));
    }
    return List.copyOf(failures);
  }

  private static boolean quoted(Object source, Object quote) {
    if (quote instanceof List<?> fragments)
      return !fragments.isEmpty() && fragments.size() <= 6 && fragments.stream()
          .allMatch(fragment -> fragment instanceof String && quoted(source, fragment));
    String fragment = text(quote).replaceAll("\\s+", "");
    return fragment.length() >= 4 && text(source).replaceAll("\\s+", "").contains(fragment);
  }
  private static String text(Object value) { return Objects.toString(value, "").trim(); }
}
