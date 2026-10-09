package com.tikuzhushou.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Shared policy consumed by planning, all export formats, and the frontend JSON import. */
public final class QuestionTypeOrder {
  public static final String VERSION = "QUESTION_TYPE_GROUPED_V1";
  private static final List<Type> TYPES = load();
  private QuestionTypeOrder() { }

  private static List<Type> load() {
    try (InputStream input = QuestionTypeOrder.class.getResourceAsStream("/question-types.json")) {
      if (input == null) throw new IllegalStateException("缺少题型排序配置");
      return List.copyOf(new ObjectMapper().readValue(input, new TypeReference<List<Type>>() { }));
    } catch (Exception error) { throw new IllegalStateException("读取题型排序配置失败", error); }
  }

  public static String canonical(String value) {
    String normalized = Objects.toString(value, "").toUpperCase(Locale.ROOT).replaceAll("[\\s_/-]", "");
    for (Type type : TYPES) {
      if (normalized.equals(type.code().replace("_", ""))
          || type.aliases().stream().anyMatch(normalized::contains)) return type.code();
    }
    return "CUSTOM";
  }

  public static String key(String code, String label) {
    String canonical = canonical(code);
    if ("CUSTOM".equals(canonical)) canonical = canonical(label);
    return "CUSTOM".equals(canonical) ? "CUSTOM:" + Objects.toString(label, code).trim() : canonical;
  }

  public static int rank(String code, String label) {
    String key = key(code, label);
    for (int index = 0; index < TYPES.size(); index++) if (TYPES.get(index).code().equals(key)) return index;
    return TYPES.size();
  }

  public static String label(String code, String fallback) {
    String key = key(code, fallback);
    return TYPES.stream().filter(type -> type.code().equals(key)).map(Type::label).findFirst()
        .orElse(Objects.toString(fallback, code));
  }

  private record Type(String code, String label, List<String> aliases) { }
}
