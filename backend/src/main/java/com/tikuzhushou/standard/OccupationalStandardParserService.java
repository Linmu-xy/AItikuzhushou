package com.tikuzhushou.standard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.ai.DeepSeekService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OccupationalStandardParserService {
  private static final Pattern CODE = Pattern.compile("职业(?:工种)?编码[：: ]*([0-9-]{6,20})");
  private static final Pattern PROFESSION = Pattern.compile("职业名称[：: ]*([^\\n]{2,40})");
  private static final List<String> LEVEL_NAMES = List.of("五级/初级工", "四级/中级工", "三级/高级工", "二级/技师", "一级/高级技师");

  private final DeepSeekService ai;
  private final ObjectMapper json;
  private final boolean modelEnabled;

  public OccupationalStandardParserService(DeepSeekService ai, ObjectMapper json,
      @Value("${app.ai.standard-extraction-enabled:true}") boolean modelEnabled) {
    this.ai = ai;
    this.json = json;
    this.modelEnabled = modelEnabled;
  }

  public Extraction parse(String fullText) {
    if (fullText == null || fullText.replaceAll("\\s", "").length() < 300) {
      throw new IllegalArgumentException("有效正文过少，不能生成评审标准，请先修正 OCR 结果");
    }
    if (!modelEnabled) return deterministic(fullText, List.of());
    List<String> diagnostics = new ArrayList<>();
    List<String> detectedLevels = detectLevels(fullText);
    if (detectedLevels.stream().noneMatch(level -> level.contains("待人工"))) {
      try {
        return modelExtractByLevel(fullText, detectedLevels, diagnostics);
      } catch (Exception error) {
        diagnostics.add("LEVEL_SCOPED_EXTRACTION：" + diagnostic(error));
      }
    }
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        return modelExtract(fullText, attempt, diagnostics);
      } catch (Exception error) {
        diagnostics.add("MODEL_ATTEMPT_" + attempt + "：" + diagnostic(error));
      }
    }
    return deterministic(fullText, diagnostics);
  }

  /**
   * First-stage extraction: ask the model to identify profession metadata and explicit level
   * headings. Rules are a guarded fallback only, so a transient model failure never blocks the
   * later per-level workflow.
   */
  public LevelDiscovery discover(String fullText) {
    if (fullText == null || fullText.replaceAll("\\s", "").length() < 300) {
      throw new IllegalArgumentException("有效正文过少，不能识别职业等级，请先修正 OCR 结果");
    }
    String ruleProfession = professionFromSource(fullText);
    String ruleCode = find(fullText, CODE, "");
    List<String> ruleLevels = canonicalLevels(detectLevels(fullText));
    if (!modelEnabled) return new LevelDiscovery(ruleProfession, ruleCode, ruleLevels, !ruleLevels.isEmpty());

    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        Map<String, Object> data = parseJson(ai.analyseJson("""
            你是国家职业技能标准识别专家。只识别职业名称、职业编码和原文明确设置的职业等级；
            不要抽取技能要求、考点或目录内容，也不要推测文中没有的等级。
            输出 JSON：{"profession":"","occupationCode":"","levels":["三级/高级工"]}。
            levels 只允许使用：五级/初级工、四级/中级工、三级/高级工、二级/技师、一级/高级技师；
            未出现明确等级时输出空数组。
            """, """
            请从以下职业标准原文中识别职业名称、职业编码和职业等级。等级必须能在原文定位。

            【职业标准原文】
            %s
            """.formatted(selectEvidence(fullText)), 2048));
        List<String> levels = canonicalLevels(cleanList(data.get("levels"), LEVEL_NAMES.size()));
        // A model result is accepted only when every selected level has an original-text anchor.
        levels = levels.stream().filter(level -> levelAnchor(fullText, level) >= 0).toList();
        // OCR often writes a level as “国家职业资格三级” and omits its common title
        // “高级工”; source anchoring above is the authoritative anti-hallucination check.
        // Do not discard a model-recognised, source-anchored level merely because the
        // lightweight rule recogniser could not reconstruct that title pair.
        String profession = cleanScalar(data.get("profession"));
        String code = cleanScalar(data.get("occupationCode"));
        // Keep model output as the primary result, but merge explicit rule-detected headings so
        // an otherwise valid model response cannot silently omit one level from a five-level
        // standard. Both sources are anchored in the supplied document.
        List<String> completeLevels = new ArrayList<>(levels); completeLevels.addAll(ruleLevels);
        completeLevels = canonicalLevels(completeLevels);
        return new LevelDiscovery(profession.isBlank() ? ruleProfession : profession,
            code.isBlank() ? ruleCode : code, completeLevels, !completeLevels.isEmpty());
      } catch (Exception error) {
        // Retry malformed, incomplete, or ungrounded model output before using the rule fallback.
      }
    }
    // The model has been attempted and failed quality validation; preserve a usable flow with
    // deterministic, source-anchored recognition instead of fabricating a model result.
    return new LevelDiscovery(ruleProfession, ruleCode, ruleLevels, !ruleLevels.isEmpty());
  }

  /** Second-stage extraction for one selected level only. */
  public Extraction parseLevel(String fullText, String level) {
    if (level == null || level.isBlank()) throw new IllegalArgumentException("必须选择一个职业等级");
    if (!modelEnabled) {
      Extraction draft = deterministic(selectLevelEvidence(fullText, level), List.of("RULE_FALLBACK：按所选等级原文生成草稿"));
      return new Extraction(draft.profession(), draft.occupationCode(), List.of(level), draft.assessmentPoints(),
          fallbackLevelDetails(List.of(level), draft.assessmentPoints()), draft.method(), draft.diagnostics());
    }
    List<String> diagnostics = new ArrayList<>();
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        LevelScopedExtraction extracted = modelExtractLevel(fullText, level);
        List<LevelDetail> details = List.of(new LevelDetail(level, extracted.outline()));
        List<String> points = flattenedDetailPoints(details);
        String profession = extracted.profession().isBlank() ? discover(fullText).profession() : extracted.profession();
        String code = extracted.occupationCode().isBlank() ? discover(fullText).occupationCode() : extracted.occupationCode();
        if (profession.isBlank() || "未识别职业".equals(profession) || points.size() < 5) {
          throw new IllegalStateException("职业名称或该等级有效考点不足");
        }
        diagnostics.add("LEVEL_" + level + "_ATTEMPT_" + attempt + "：SUCCESS");
        return new Extraction(profession, code, List.of(level), points, details, "MODEL_LEVEL_SCOPED", List.copyOf(diagnostics));
      } catch (Exception error) {
        diagnostics.add("LEVEL_" + level + "_ATTEMPT_" + attempt + "：" + diagnostic(error));
      }
    }
    throw new IllegalStateException("等级“" + level + "”连续提取失败：" + diagnostics.getLast());
  }

  /**
   * New standards are extracted once per level rather than asking the model to infer a level
   * relationship after producing one mixed list.  This is deliberately separate requests:
   * each request receives the source span around the selected level and cannot borrow a sibling
   * level's evidence.
   */
  private Extraction modelExtractByLevel(String fullText, List<String> levels, List<String> diagnostics) {
    String profession = find(fullText, PROFESSION, "");
    String code = find(fullText, CODE, "");
    List<LevelDetail> details = new ArrayList<>();
    for (String level : levels) {
      LevelScopedExtraction extracted = null;
      Exception last = null;
      for (int attempt = 1; attempt <= 3; attempt++) {
        try {
          extracted = modelExtractLevel(fullText, level);
          diagnostics.add("LEVEL_" + level + "_ATTEMPT_" + attempt + "：SUCCESS");
          break;
        } catch (Exception error) {
          last = error;
          diagnostics.add("LEVEL_" + level + "_ATTEMPT_" + attempt + "：" + diagnostic(error));
        }
      }
      if (extracted == null) throw new IllegalStateException("等级“" + level + "”提取失败：" + rootMessage(last));
      if (profession.isBlank()) profession = extracted.profession();
      if (code.isBlank()) code = extracted.occupationCode();
      details.add(new LevelDetail(level, extracted.outline()));
    }
    List<String> points = flattenedDetailPoints(details);
    if (profession.isBlank() || points.size() < 5 || details.size() != levels.size()) {
      throw new IllegalStateException("分等级抽取的职业名称、等级细目表或有效考点不足");
    }
    return new Extraction(profession, code, levels, points, details, "MODEL_LEVEL_SCOPED", List.copyOf(diagnostics));
  }

  private LevelScopedExtraction modelExtractLevel(String fullText, String level) {
    String evidence = selectLevelEvidence(fullText, level);
    String system = """
        你是国家职业技能标准结构化抽取专家。你当前只处理一个职业等级。
        只能抽取【当前职业等级】明确适用的内容；不得引用、改写或下放其他等级的能力要求。
        请输出 JSON 对象，包含 profession、occupationCode 和 outline。outline 是 1~3 层能力树：
        每层有 code、name、weight（原文没有则 null）、relatedKnowledgeRequirement、children、assessmentPoints。
        每个 assessmentPoints 有 code、name、importance（原文没有则“待人工确认”）、skillRequirement、
        relatedKnowledgeRequirement、sourceEvidence。sourceEvidence 必须是原文可定位的短句。
        """;
    String prompt = """
        当前职业等级：%s
        请只依据以下该等级附近的原文，输出该等级的考评细目 JSON。
        要求：每个末级考点必须有技能要求；目录、申报条件、前言和没有要求正文的标题不得作为考点。
        原文没有的编码、权重、重要度不可推测。最多 80 个考点。

        【当前职业等级原文】
        %s
        """.formatted(level, evidence);
    try {
      Map<String, Object> data = parseJson(ai.analyseJson(system, prompt, 6144));
      List<StandardOutlineNode> outline = cleanOutline(data.get("outline"), evidence, 0);
      if (flattenedOutlinePoints(outline).isEmpty()) {
        throw new IllegalStateException("当前等级没有通过原文校验的完整考点");
      }
      return new LevelScopedExtraction(cleanScalar(data.get("profession")), cleanScalar(data.get("occupationCode")), outline);
    } catch (Exception error) {
      throw new IllegalStateException("等级“" + level + "”结构化抽取未通过质量门禁：" + rootMessage(error), error);
    }
  }

  private Extraction modelExtract(String fullText, int attempt, List<String> diagnostics) {
    String evidence = selectEvidence(fullText);
    String system = """
        你是国家职业技能标准结构化抽取专家。任务是忠实抽取，不是总结或创作。
        必须按职业等级分别形成细目表。每个 levelDetails 项只能放该等级明确适用的内容；共同内容可在多个等级分别出现，但不得把高等级内容下放到低等级。
        每个 assessmentPoints 项必须是可直接用于命题的完整考核单元，并同时包含职业功能/工作内容、技能要求和相关知识；不得把目录、前言、申报条件或一句残句当作考点。
        输出 JSON 格式示例：
        {"profession":"公共营养师","occupationCode":"4-14-02-01","levels":["三级/高级工"],
         "assessmentPoints":["职业功能：膳食调查与评价｜工作内容：食物摄入量调查｜技能要求：能使用称重法开展调查｜相关知识：称重法的技术要求"],
         "levelDetails":[{"level":"三级/高级工","outline":[{"code":"A","name":"膳食调查与评价","weight":null,"relatedKnowledgeRequirement":"称重法的技术要求","children":[{"code":"A.1","name":"食物摄入量调查","weight":null,"relatedKnowledgeRequirement":"称重法的技术要求","children":[],"assessmentPoints":[{"code":"001","name":"称重法调查","importance":"重要","skillRequirement":"能使用称重法开展调查","relatedKnowledgeRequirement":"称重法的技术要求","sourceEvidence":"能使用称重法开展食物摄入量调查"}]}],"assessmentPoints":[]}]}]}
        """;
    String prompt = """
        请从【职业标准原文】抽取 JSON。要求：
        1. profession 和 occupationCode 按原文；levels 只保留原文明确设置的等级；
        2. levelDetails 按等级分别输出。outline 是 1~3 层能力树；每层包含 code、name、weight（原文没有则 null）、relatedKnowledgeRequirement、children、assessmentPoints；
        3. assessmentPoints 去重，优先覆盖所有“职业功能—工作内容—技能要求—相关知识”组合；每个 levelDetails.assessmentPoints 必须给出 code、name、importance、skillRequirement、relatedKnowledgeRequirement、sourceEvidence；
        4. sourceEvidence 必须是能在原文中找到的短证据，code、weight、importance 原文没有时分别为空、null、"待人工确认"，绝不自行编造；
        5. 每项 assessmentPoints 20~180 个汉字，使用“职业功能：…｜工作内容：…｜技能要求：…｜相关知识：…”格式；
        6. 原文没有的字段写空字符串，绝不推测；最多 120 项。

        【职业标准原文】
        %s
        """.formatted(evidence);
    try {
      String raw = ai.analyseJson(system, prompt, 8192);
      Map<String, Object> data = parseJson(raw);
      String profession = cleanScalar(data.get("profession"));
      String code = cleanScalar(data.get("occupationCode"));
      List<String> levels = cleanList(data.get("levels"), 10);
      if (levels.isEmpty()) levels = detectLevels(fullText);
      List<LevelDetail> levelDetails = cleanLevelDetails(data.get("levelDetails"), fullText, levels);
      List<String> points = cleanPoints(data.get("assessmentPoints"), fullText);
      if (points.isEmpty()) points = flattenedDetailPoints(levelDetails);
      if (profession.isBlank() || points.size() < 5 || levelDetails.size() != levels.size()) {
        throw new IllegalStateException("模型抽取的职业名称、分级细目表或有效考点不足");
      }
      diagnostics.add("MODEL_ATTEMPT_" + attempt + "：SUCCESS");
      return new Extraction(profession, code, levels, points, levelDetails,
          "MODEL_GROUNDED", List.copyOf(diagnostics));
    } catch (Exception e) {
      throw new IllegalStateException("职业标准结构化抽取未通过质量门禁：" + rootMessage(e), e);
    }
  }

  private Map<String, Object> parseJson(String raw) throws Exception {
    String cleaned = stripFence(raw);
    try { return json.readValue(cleaned, new TypeReference<>() { }); }
    catch (Exception first) {
      int begin = cleaned.indexOf('{'), end = cleaned.lastIndexOf('}');
      if (begin < 0 || end <= begin) throw new IllegalStateException("MODEL_JSON_INVALID：未找到完整 JSON 对象", first);
      String candidate = cleaned.substring(begin, end + 1)
          .replaceAll(",\\s*([}\\]])", "$1");
      try { return json.readValue(candidate, new TypeReference<>() { }); }
      catch (Exception second) { throw new IllegalStateException("MODEL_JSON_INVALID：JSON 修复后仍不可解析", second); }
    }
  }

  private Extraction deterministic(String text, List<String> diagnostics) {
    String profession = find(text, PROFESSION, "未识别职业");
    if ("未识别职业".equals(profession)) {
      Matcher matcher = Pattern.compile("([\\u4e00-\\u9fa5]{2,16})国家职业技能标准").matcher(text);
      if (matcher.find()) profession = matcher.group(1);
    }
    List<String> points = Arrays.stream(text.split("[。；;\\n]"))
        .map(String::trim).filter(s -> s.length() >= 15 && s.length() <= 180)
        .filter(s -> s.contains("能够") || s.contains("能 ") || s.contains("技能要求") || s.contains("相关知识"))
        .distinct().limit(100).toList();
    if (points.size() < 5) points = lenientDraftPoints(text);
    if (points.size() < 5) throw new IllegalStateException("RULE_FALLBACK_INSUFFICIENT：规则草稿不足 5 个可编辑考点");
    List<String> notes = new ArrayList<>(diagnostics);
    notes.add("RULE_FALLBACK：模型连续失败，已生成待人工核对的规则抽取草稿");
    List<String> levels = detectLevels(text);
    return new Extraction(profession, find(text, CODE, ""), levels, points, fallbackLevelDetails(levels, points),
        "RULE_FALLBACK", List.copyOf(notes));
  }

  private List<LevelDetail> cleanLevelDetails(Object value, String source, List<String> expectedLevels) {
    if (!(value instanceof List<?> list)) return List.of();
    List<LevelDetail> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Object raw : list) {
      if (!(raw instanceof Map<?, ?> row)) continue;
      String level = cleanScalar(row.get("level"));
      if (level.isBlank() || !expectedLevels.contains(level) || !seen.add(level)) continue;
      List<StandardOutlineNode> outline = cleanOutline(row.get("outline"), source, 0);
      if (flattenedOutlinePoints(outline).isEmpty()) continue;
      result.add(new LevelDetail(level, outline));
    }
    return result;
  }

  private List<StandardOutlineNode> cleanOutline(Object value, String source, int depth) {
    if (!(value instanceof List<?> list) || depth >= 3) return List.of();
    List<StandardOutlineNode> result = new ArrayList<>();
    for (Object raw : list) {
      if (!(raw instanceof Map<?, ?> row)) continue;
      String name = cleanScalar(row.get("name"));
      if (name.isBlank() || name.length() > 100 || grounding(name, source) < 0.08) continue;
      String code = cleanScalar(row.get("code"));
      Integer weight = number(row.get("weight"));
      String knowledge = cleanScalar(row.get("relatedKnowledgeRequirement"));
      List<StandardOutlineNode> children = cleanOutline(row.get("children"), source, depth + 1);
      List<LevelAssessmentPoint> points = cleanLevelPoints(row.get("assessmentPoints"), source);
      if (children.isEmpty() && points.isEmpty()) continue;
      result.add(new StandardOutlineNode(code, name, weight, knowledge, children, points));
    }
    return result;
  }

  private List<LevelAssessmentPoint> cleanLevelPoints(Object value, String source) {
    if (!(value instanceof List<?> list)) return List.of();
    List<LevelAssessmentPoint> result = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
    for (Object raw : list) {
      if (!(raw instanceof Map<?, ?> row)) continue;
      String skill = cleanScalar(row.get("skillRequirement"));
      String knowledge = cleanScalar(row.get("relatedKnowledgeRequirement"));
      String name = cleanScalar(row.get("name"));
      String point = "职业功能：｜工作内容：" + name + "｜技能要求：" + skill + "｜相关知识：" + knowledge;
      if (name.isBlank() || skill.isBlank() || grounding(point, source) < 0.08 || !seen.add(name + "|" + skill)) continue;
      String evidence = cleanScalar(row.get("sourceEvidence"));
      if (!evidence.isBlank() && grounding(evidence, source) < 0.12) evidence = "";
      result.add(new LevelAssessmentPoint(cleanScalar(row.get("code")), name, cleanScalar(row.get("importance")),
          skill, knowledge, evidence));
    }
    return result;
  }

  private Integer number(Object value) {
    if (value instanceof Number number) return number.intValue();
    try { return Integer.valueOf(cleanScalar(value)); } catch (Exception ignored) { return null; }
  }

  private List<String> flattenedDetailPoints(List<LevelDetail> details) {
    List<String> result = new ArrayList<>();
    for (LevelDetail detail : details) result.addAll(flattenedOutlinePoints(detail.outline()));
    return result.stream().distinct().limit(120).toList();
  }

  private List<String> flattenedOutlinePoints(List<StandardOutlineNode> outline) {
    List<String> result = new ArrayList<>();
    for (StandardOutlineNode node : outline) {
      for (LevelAssessmentPoint point : node.assessmentPoints()) result.add("职业功能：" + node.name()
          + "｜工作内容：" + point.name() + "｜技能要求：" + point.skillRequirement()
          + "｜相关知识：" + point.relatedKnowledgeRequirement());
      result.addAll(flattenedOutlinePoints(node.children()));
    }
    return result;
  }

  private List<LevelDetail> fallbackLevelDetails(List<String> levels, List<String> points) {
    List<LevelAssessmentPoint> rows = new ArrayList<>(); int index = 1;
    for (String point : points) rows.add(new LevelAssessmentPoint(String.format("%03d", index++), point,
        "待人工确认", point, "", ""));
    List<StandardOutlineNode> outline = List.of(new StandardOutlineNode("", "待人工归类", null, "", List.of(), rows));
    return levels.stream().map(level -> new LevelDetail(level, outline)).toList();
  }

  private List<String> lenientDraftPoints(String text) {
    return Arrays.stream(text.split("[。；;\\n]"))
        .map(String::trim).filter(value -> value.length() >= 18 && value.length() <= 180)
        .filter(value -> value.contains("原料") || value.contains("操作") || value.contains("加工") || value.contains("烹调") || value.contains("安全"))
        .distinct().limit(20).toList();
  }

  private String selectEvidence(String text) {
    String normalized = text.replace("\r", "").replaceAll("[ \\t]+", " ");
    StringBuilder result = new StringBuilder(normalized.substring(0, Math.min(9000, normalized.length())));
    for (String line : normalized.split("\\n")) {
      String value = line.trim();
      if (value.length() < 6 || value.length() > 260) continue;
      if (value.matches(".*(职业功能|工作内容|技能要求|相关知识|考核|能够|\\b能\\b).*")) {
        result.append('\n').append(value);
        if (result.length() >= 48_000) break;
      }
    }
    return result.substring(0, Math.min(result.length(), 48_000));
  }

  private String selectLevelEvidence(String text, String level) {
    String normalized = text.replace("\r", "");
    // Only a chapter/table title can start a level evidence window.  A level name in the
    // occupational-level list or application requirements is evidence that the level exists,
    // but it is not the beginning of that level's work requirements.
    int start = levelBodyAnchor(normalized, level);
    if (start < 0) return selectEvidence(normalized);
    int end = normalized.length();
    for (String candidate : LEVEL_NAMES) {
      if (candidate.equals(level)) continue;
      int position = levelBodyAnchor(normalized, candidate);
      if (position > start && position < end) end = position;
    }
    // Keep the nearest title/table context while preventing a long document from leaking a
    // subsequent level into the model request.
    int contextStart = Math.max(0, start - 1_200);
    String section = normalized.substring(contextStart, Math.min(end, contextStart + 52_000));
    return selectEvidence(section);
  }

  /**
   * Finds a level's actual work-requirements heading, including a Markdown table title.
   * Deliberately does not fall back to an arbitrary occurrence of the level label: those labels
   * commonly occur in the level catalogue and eligibility requirements before chapter 3.
   */
  private int levelBodyAnchor(String text, String level) {
    String label = levelHeadingLabel(level);
    String suffix = "(?:\\s*(?:工作要求|职业技能要求)?(?:\\s*[（(]续[）)])?)?";
    Matcher chapter = Pattern.compile("(?m)^\\s*#{1,6}\\s*(?:3\\.\\d+\\s*)?" + label + suffix + "\\s*$")
        .matcher(text);
    if (chapter.find()) return chapter.start();
    Matcher tableTitle = Pattern.compile("(?m)^\\s*(?:\\*{1,2}\\s*)?(?:3\\.\\d+\\s*)?" + label + suffix
        + "(?:\\s*\\*{1,2})?\\s*$").matcher(text);
    return tableTitle.find() ? tableTitle.start() : -1;
  }

  private String levelHeadingLabel(String level) {
    String[] parts = level.split("/");
    if (parts.length < 2) return Pattern.quote(level);
    // The full label is preferred, while older standards that use only the title still work.
    // The expression is line-anchored by levelBodyAnchor, so “技师” cannot match “高级技师”.
    return "(?:" + Pattern.quote(parts[0]) + "\\s*/\\s*" + Pattern.quote(parts[1]) + "|"
        + Pattern.quote(parts[1]) + ")";
  }

  private int levelAnchor(String text, String level) {
    int bodyAnchor = levelBodyAnchor(text, level);
    if (bodyAnchor >= 0) return bodyAnchor;
    String[] parts = level.split("/");
    int direct = text.indexOf(level);
    if (direct >= 0) return direct;
    int grade = text.indexOf(parts[0]);
    if (grade >= 0) return grade;
    return parts.length > 1 ? text.indexOf(parts[1]) : -1;
  }

  private List<String> cleanPoints(Object value, String source) {
    List<String> values = cleanList(value, 120);
    Set<String> unique = new LinkedHashSet<>();
    for (String point : values) {
      String clean = point.replaceAll("^[0-9.、 ]+", "").replaceAll("\\s+", " ").trim();
      if (clean.length() < 18 || clean.length() > 220) continue;
      if (!(clean.contains("技能要求") || clean.contains("能够") || clean.contains("能"))) continue;
      if (grounding(clean, source) < 0.08) continue;
      unique.add(clean);
    }
    return new ArrayList<>(unique);
  }

  private double grounding(String point, String source) {
    Set<String> query = grams(normalize(point));
    Set<String> body = grams(normalize(source));
    if (query.isEmpty()) return 0;
    long matched = query.stream().filter(body::contains).count();
    return matched / (double) query.size();
  }

  private Set<String> grams(String value) {
    Set<String> result = new LinkedHashSet<>();
    for (int i = 0; i + 1 < value.length(); i++) result.add(value.substring(i, i + 2));
    return result;
  }

  private String normalize(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]", "");
  }

  private List<String> cleanList(Object value, int max) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream().map(this::cleanScalar).filter(s -> !s.isBlank()).distinct().limit(max).toList();
  }

  private String professionFromSource(String text) {
    String profession = find(text, PROFESSION, "未识别职业");
    if (!"未识别职业".equals(profession)) return profession;
    Matcher matcher = Pattern.compile("([\\u4e00-\\u9fa5]{2,16})国家职业技能标准").matcher(text);
    return matcher.find() ? matcher.group(1) : profession;
  }

  private List<String> canonicalLevels(List<String> values) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String value : values) {
      String compact = cleanScalar(value).replaceAll("\\s", "");
      for (String level : LEVEL_NAMES) {
        String[] parts = level.split("/");
        if (compact.equals(level) || compact.equals(parts[0]) || compact.equals(parts[1])
            || (compact.contains(parts[0]) && compact.contains(parts[1]))) {
          result.add(level);
          break;
        }
      }
    }
    return LEVEL_NAMES.stream().filter(result::contains).toList();
  }

  private List<String> detectLevels(String text) {
    List<String> values = new ArrayList<>();
    String compactText = text.replaceAll("\\s+", "");
    for (String level : LEVEL_NAMES) {
      String[] parts = level.split("/");
      if (compactText.contains(level) || (compactText.contains(parts[0]) && compactText.contains(parts[1]))) values.add(level);
      else {
        // Older standards commonly write “中级（国家职业资格四级）” instead of
        // “四级/中级工”. Require both labels near each other so isolated mentions do not
        // accidentally create a level.
        String title = parts[1].replace("工", "");
        if (Pattern.compile(title + "[\\s\\S]{0,48}" + parts[0] + "|" + parts[0] + "[\\s\\S]{0,48}" + title)
            .matcher(compactText).find()) values.add(level);
      }
    }
    if (values.isEmpty()) {
      // Older standards often write only the title, for example “高级工” instead of
      // “三级/高级工”.  Normalize those documents into the current level vocabulary.
      for (String level : List.of("一级/高级技师", "二级/技师", "三级/高级工", "四级/中级工", "五级/初级工")) {
        String title = level.substring(level.indexOf('/') + 1);
        if (text.contains(title)) values.add(level);
      }
    }
    if (values.isEmpty()) {
      for (String value : List.of("一级", "二级", "三级", "四级", "五级")) if (text.contains(value)) values.add(value);
    }
    return values.isEmpty() ? List.of("待人工确认") : values;
  }

  private String cleanScalar(Object value) {
    return Objects.toString(value, "").replaceAll("[\\r\\n\\t]+", " ").trim();
  }

  private String stripFence(String value) {
    return value.trim().replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("(?s)\\s*```$", "");
  }

  private String find(String text, Pattern pattern, String fallback) {
    Matcher matcher = pattern.matcher(text);
    return matcher.find() ? matcher.group(1).trim().replaceAll("[。；;].*", "") : fallback;
  }

  private String rootMessage(Exception error) {
    Throwable value = error;
    while (value.getCause() != null) value = value.getCause();
    return Objects.toString(value.getMessage(), value.getClass().getSimpleName());
  }

  private String diagnostic(Exception error) {
    String message = rootMessage(error).replaceAll("[\\r\\n]+", " ").trim();
    return (message.isBlank() ? error.getClass().getSimpleName() : message).substring(0,
        Math.min(320, Math.max(1, message.isBlank() ? error.getClass().getSimpleName().length() : message.length())));
  }

  public record Extraction(String profession, String occupationCode, List<String> levels,
      List<String> assessmentPoints, List<LevelDetail> levelDetails, String method, List<String> diagnostics) { }
  public record LevelDetail(String level, List<StandardOutlineNode> outline) { }
  public record StandardOutlineNode(String code, String name, Integer weight, String relatedKnowledgeRequirement,
      List<StandardOutlineNode> children, List<LevelAssessmentPoint> assessmentPoints) { }
  public record LevelAssessmentPoint(String code, String name, String importance, String skillRequirement,
      String relatedKnowledgeRequirement, String sourceEvidence) { }
  public record LevelDiscovery(String profession, String occupationCode, List<String> levels, boolean levelDetected) { }
  private record LevelScopedExtraction(String profession, String occupationCode, List<StandardOutlineNode> outline) { }
}
