package com.tikuzhushou.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.document.DocumentParsingService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import com.tikuzhushou.question.QuestionProfessionalReviewService;
import com.tikuzhushou.question.QuestionContextAssetService;
import com.tikuzhushou.question.QuestionRefinementService;
import com.tikuzhushou.retrieval.RetrievalService;
import com.tikuzhushou.standard.OccupationalStandardParserService;
import com.tikuzhushou.standard.OccupationalStandardParserService.LevelDetail;
import com.tikuzhushou.workflow.GenerationProgressService;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CoreBusinessService {
  /** Contracted examination types. Do not add convenience types: customer acceptance checks these nine. */
  private static final List<String> TYPES = List.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE",
      "FILL_BLANK", "SHORT_ANSWER", "CALCULATION", "ESSAY", "CASE_ANALYSIS", "COMPREHENSIVE");
  private final DocumentParsingService parsing;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final RetrievalService retrieval;
  private final QuestionRefinementService refiner;
  private final QuestionContextAssetService contextAssets;
  private final QuestionProfessionalReviewService professionalReviewer;
  private final OccupationalStandardParserService standardParser;
  private final KnowledgeBaseAccessService access;
  private final GenerationProgressService generationProgress;
  private final Executor questionGenerationExecutor;
  private final int questionConcurrency;
  private final int optionRewriteMaxAttempts;
  private final int fullRewriteMaxAttempts;
  private final Map<UUID, Standard> standards = new ConcurrentHashMap<>();
  private final Map<UUID, StandardExtractionInfo> standardExtractionInfo = new ConcurrentHashMap<>();
  private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

  public CoreBusinessService(DocumentParsingService parsing, JdbcTemplate jdbc, ObjectMapper json,
      RetrievalService retrieval, QuestionRefinementService refiner, QuestionContextAssetService contextAssets,
      QuestionProfessionalReviewService professionalReviewer,
      OccupationalStandardParserService standardParser, KnowledgeBaseAccessService access,
      GenerationProgressService generationProgress,
      @Qualifier("questionGenerationExecutor") Executor questionGenerationExecutor,
      @Value("${app.ai.question-concurrency:5}") int questionConcurrency,
      @Value("${app.ai.option-rewrite-max-attempts:1}") int optionRewriteMaxAttempts,
      @Value("${app.ai.question-full-rewrite-max-attempts:2}") int fullRewriteMaxAttempts) {
    this.parsing = parsing;
    this.jdbc = jdbc;
    this.json = json;
    this.retrieval = retrieval;
    this.refiner = refiner;
    this.contextAssets = contextAssets;
    this.professionalReviewer = professionalReviewer;
    this.standardParser = standardParser;
    this.access = access;
    this.generationProgress = generationProgress;
    this.questionGenerationExecutor = questionGenerationExecutor;
    this.questionConcurrency = Math.max(1, Math.min(questionConcurrency, 12));
    this.optionRewriteMaxAttempts = Math.max(0, Math.min(optionRewriteMaxAttempts, 2));
    this.fullRewriteMaxAttempts = Math.max(1, Math.min(fullRewriteMaxAttempts, 3));
  }

  public Standard extractStandard(UUID documentId) {
    var document = parsing.get(documentId);
    if (!"PARSED".equals(document.status())) {
      throw new IllegalArgumentException("文档 OCR/文字质量未达标，必须先在文档预览中修正后再结构化");
    }
    var extracted = standardParser.parse(standardSource(document));
    var result = new Standard(UUID.randomUUID(), documentId, null, "GENERAL_STANDARD", extracted.profession(),
        extracted.occupationCode(), extracted.levels(), extracted.assessmentPoints(), extracted.levelDetails(),
        false, 1, "DRAFT", Instant.now());
    standards.put(result.id(), result);
    standardExtractionInfo.put(result.id(), new StandardExtractionInfo(extracted.method(), extracted.diagnostics()));
    persistStandard(result);
    persistVersion(result);
    return result;
  }

  /** First step of the new workflow: identify only explicit occupational levels. */
  public Standard discoverLevels(UUID documentId) {
    var document = parsing.get(documentId);
    if (!"PARSED".equals(document.status())) {
      throw new IllegalArgumentException("文档 OCR/文字质量未达标，必须先在文档预览中修正后再识别职业等级");
    }
    var discovered = standardParser.discover(standardSource(document));
    String kind = discovered.levelDetected() ? "LEVEL_DISCOVERY" : "GENERAL_STANDARD";
    String status = discovered.levelDetected() ? "LEVELS_DISCOVERED" : "LEVEL_NOT_DETECTED";
    var result = new Standard(UUID.randomUUID(), documentId, null, kind, discovered.profession(),
        discovered.occupationCode(), discovered.levels(), List.of(), List.of(), false, 1, status, Instant.now());
    standards.put(result.id(), result);
    persistStandard(result);
    persistVersion(result);
    return result;
  }

  /** Creates an independent child standard for one level. Its task extracts only that level's source span. */
  public Standard prepareLevelStandard(UUID discoveryId, String level) {
    Standard parent = getStandard(discoveryId);
    if (!"LEVEL_DISCOVERY".equals(parent.standardKind())) {
      throw new IllegalArgumentException("只能从职业等级识别结果创建等级标准");
    }
    if (level == null || !parent.levels().contains(level)) {
      throw new IllegalArgumentException("所选职业等级不属于当前识别结果");
    }
    var result = new Standard(UUID.randomUUID(), parent.documentId(), parent.id(), "LEVEL_STANDARD",
        parent.profession(), parent.occupationCode(), List.of(level), List.of(), List.of(), false,
        1, "QUEUED", Instant.now());
    standards.put(result.id(), result);
    persistStandard(result);
    persistVersion(result);
    return result;
  }

  /** Completes a prepared child standard. A failure leaves sibling level standards untouched. */
  public Standard extractPreparedLevelStandard(UUID standardId) {
    Standard pending = getStandard(standardId);
    if (!"LEVEL_STANDARD".equals(pending.standardKind()) || pending.levels().size() != 1) {
      throw new IllegalArgumentException("当前记录不是待提取的职业等级标准");
    }
    var document = parsing.get(pending.documentId());
    if (!"PARSED".equals(document.status())) {
      throw new IllegalArgumentException("源文档未通过解析质量校验");
    }
    var extracted = standardParser.parseLevel(standardSource(document), pending.levels().getFirst());
    Standard result = new Standard(pending.id(), pending.documentId(), pending.parentStandardId(), pending.standardKind(),
        extracted.profession(), extracted.occupationCode(), extracted.levels(), extracted.assessmentPoints(),
        extracted.levelDetails(), false, pending.version() + 1, "DRAFT", Instant.now());
    standards.put(result.id(), result);
    standardExtractionInfo.put(result.id(), new StandardExtractionInfo(extracted.method(), extracted.diagnostics()));
    persistStandard(result);
    persistVersion(result);
    return result;
  }

  public StandardExtractionInfo standardExtractionInfo(UUID standardId) {
    return standardExtractionInfo.getOrDefault(standardId, new StandardExtractionInfo("UNKNOWN", List.of()));
  }

  public Standard confirm(UUID id) {
    var old = getStandard(id);
    validateStandard(old.profession(), old.levels(), old.assessmentPoints());
    var confirmed = new Standard(old.id(), old.documentId(), old.parentStandardId(), old.standardKind(),
        old.profession(), old.occupationCode(),
        old.levels(), old.assessmentPoints(), standardLevelDetails(old), old.levelDetailsDerived(),
        old.version(), "CONFIRMED", Instant.now());
    standards.put(id, confirmed);
    persistStandard(confirmed);
    persistVersion(confirmed);
    return confirmed;
  }

  public Standard updateStandard(UUID id, String profession, String code, List<String> levels, List<String> points) {
    var old = getStandard(id);
    List<String> cleanPoints = points == null ? List.of() : points.stream().map(String::trim)
        .filter(value -> !value.isBlank()).distinct().toList();
    List<String> cleanLevels = levels == null || levels.isEmpty() ? old.levels() : levels.stream()
        .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    validateStandard(profession, cleanLevels, cleanPoints);
    var edited = new Standard(id, old.documentId(), old.parentStandardId(), old.standardKind(),
        profession.trim(), Objects.requireNonNullElse(code, "").trim(),
        cleanLevels, cleanPoints, standardLevelDetails(old), old.levelDetailsDerived(),
        old.version() + 1, "DRAFT", Instant.now());
    standards.put(id, edited);
    persistStandard(edited);
    persistVersion(edited);
    return edited;
  }

  public List<StandardVersion> versions(UUID id) {
    getStandard(id);
    return jdbc.query("select version,status,schema_json,created_at from occupational_standard_versions where standard_id=? order by version desc",
        (rs, n) -> new StandardVersion(rs.getInt("version"), rs.getString("status"), rs.getString("schema_json"),
            rs.getTimestamp("created_at").toInstant()), id);
  }

  public Standard getStandard(UUID id) {
    var value = standards.get(id);
    if (value != null) {
      access.assertDocument(value.documentId());
      return value;
    }
    var rows = jdbc.query("select schema_json from occupational_standards where id=?", (rs, n) -> rs.getString(1), id);
    if (rows.isEmpty()) throw new IllegalArgumentException("职业标准不存在，请先解析文档");
    try {
      value = json.readValue(rows.getFirst(), Standard.class);
      access.assertDocument(value.documentId());
      standards.put(id, value);
      return value;
    } catch (org.springframework.security.access.AccessDeniedException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("职业标准数据损坏", e);
    }
  }

  /**
   * The document preview keeps table bodies separately so it can render merged cells faithfully.
   * Occupational standards, however, must give those table bodies to the model at their original
   * page position; a placeholder such as “[结构化表格:1]” has no extractable skill requirement.
   */
  private String standardSource(DocumentParsingService.ParsedDocument document) {
    Map<String, String> tables = new LinkedHashMap<>();
    for (var table : document.tables()) {
      if (table.markdown() != null && !table.markdown().isBlank()) {
        tables.put(table.page() + ":" + table.tableIndex(), table.markdown().trim());
      }
    }
    if (tables.isEmpty()) return document.fullText();
    StringBuilder source = new StringBuilder();
    int page = 0;
    Pattern pageMarker = Pattern.compile("^\\[第(\\d+)页]$");
    Pattern tableMarker = Pattern.compile("^\\[结构化表格:(\\d+)]$");
    for (String raw : document.fullText().replace("\r", "").split("\\n", -1)) {
      String line = raw.trim();
      Matcher pageMatch = pageMarker.matcher(line);
      if (pageMatch.matches()) page = Integer.parseInt(pageMatch.group(1));
      source.append(raw).append('\n');
      Matcher tableMatch = tableMarker.matcher(line);
      if (tableMatch.matches()) {
        String markdown = tables.get(page + ":" + tableMatch.group(1));
        if (markdown != null) source.append("【表格原文】\n").append(markdown).append('\n');
      }
    }
    return source.toString();
  }

  public Job blueprint(UUID standardId, int total, List<String> requestedTypes, String level) {
    return blueprint(standardId, total, requestedTypes, Map.of(), level);
  }

  /** Optional per-type allocation is authoritative when supplied; its sum must exactly equal the planned total. */
  public Job blueprint(UUID standardId, int total, List<String> requestedTypes, Map<String, Integer> typeCounts, String level) {
    var standard = getStandard(standardId);
    requireConfirmed(standard);
    validateTotal(total);
    var types = normalizeTypes(requestedTypes);
    List<String> assignedTypes = allocateTypes(types, typeCounts, total);
    List<Map<String, Object>> plan = new ArrayList<>();
    String effectiveLevel = level == null ? standard.levels().getFirst() : level;
    for (int i = 0; i < total; i++) {
      String type = assignedTypes.get(i);
      List<String> points = compatiblePoints(assessmentPointsForLevel(standard, effectiveLevel), type, i);
      String point = String.join("；", points);
      String difficulty = difficultyForLevel(effectiveLevel, i, total);
      Map<String, Object> slot = new LinkedHashMap<>();
      slot.put("sequence", i + 1); slot.put("type", type); slot.put("level", effectiveLevel);
      slot.put("assessmentPoint", point); slot.put("assessmentPoints", points); slot.put("difficulty", difficulty);
      slot.put("cognitiveTarget", cognitiveTarget(difficulty));
      slot.put("abilityObjective", abilityObjective(effectiveLevel, point, difficulty));
      slot.put("difficultyRationale", difficultyRationale(effectiveLevel, difficulty));
      plan.add(slot);
    }
    UUID knowledgeBaseId = jdbc.queryForObject("select knowledge_base_id from source_documents where id=?",
        UUID.class, standard.documentId());
    return save("BLUEPRINT", Map.of("standardId", standardId, "knowledgeBaseId", knowledgeBaseId,
        "total", total, "types", types, "typeCounts", typeCounts == null ? Map.of() : typeCounts), plan);
  }

  /**
   * Saves a human-edited blueprint without changing its planned quantity.  The generated job reads
   * this persisted plan, never a browser-only draft, so each question slot remains traceable.
   */
  public Job updateBlueprint(UUID blueprintJobId, List<Map<String, Object>> items) {
    Job current = getJob(blueprintJobId);
    if (!"BLUEPRINT".equals(current.type()) || !"SUCCEEDED".equals(current.status())) {
      throw new IllegalArgumentException("只能编辑已完成的细目表");
    }
    int expected = asNumber(current.request().get("total"), current.result().size());
    if (items == null || items.size() != expected) {
      throw new IllegalArgumentException("细目表条数必须保持为计划数量 " + expected + " 道");
    }
    Set<Integer> sequences = new LinkedHashSet<>();
    List<Map<String, Object>> cleaned = new ArrayList<>();
    Map<Integer, Map<String, Object>> originalBySequence = current.result().stream()
        .map(this::castMap).collect(Collectors.toMap(this::sequence, value -> value, (left, right) -> left, LinkedHashMap::new));
    for (Map<String, Object> item : items) {
      int sequence = asNumber(item.get("sequence"), -1);
      String type = text(item, "type");
      List<String> points = normalizeAssessmentPoints(item, type);
      String point = String.join("；", points);
      String difficulty = text(item, "difficulty");
      if (sequence < 1 || sequence > expected || !sequences.add(sequence)) {
        throw new IllegalArgumentException("细目表序号必须是 1 到 " + expected + " 且不能重复");
      }
      if (!TYPES.contains(type)) throw new IllegalArgumentException("存在不支持的题型：" + type);
      if (!Set.of("EASY", "MEDIUM", "HARD").contains(difficulty)) {
        throw new IllegalArgumentException("难度只能为 EASY、MEDIUM 或 HARD");
      }
      if (point.isBlank() || point.length() > 300) throw new IllegalArgumentException("考点不能为空且不能超过 300 字");
      Map<String, Object> original = originalBySequence.get(sequence);
      String level = original == null ? "" : text(original, "level");
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("sequence", sequence); row.put("type", type); row.put("assessmentPoint", point); row.put("assessmentPoints", points);
      row.put("difficulty", difficulty);
      row.put("level", level); row.put("cognitiveTarget", cognitiveTarget(difficulty));
      row.put("abilityObjective", abilityObjective(level, point, difficulty));
      row.put("difficultyRationale", difficultyRationale(level, difficulty));
      cleaned.add(row);
    }
    cleaned.sort(java.util.Comparator.comparingInt(this::sequence));
    Job saved = withState(current, "SUCCEEDED", 100, List.copyOf(cleaned), current.retryCount(), null);
    storeUpdate(saved);
    return saved;
  }

  public Job questions(UUID blueprintJobId) { return executeQuestionJob(queueQuestions(blueprintJobId, null, "SINGLE_REFILL").id()); }

  public Job importQuestions(List<Map<String, Object>> questions) {
    if (questions == null || questions.isEmpty()) throw new IllegalArgumentException("导入文件不包含题目");
    if (questions.size() > 1000) throw new IllegalArgumentException("单次最多导入 1000 道题");
    for (var question : questions) {
      if (text(question, "stem").isBlank() || text(question, "assessmentPoint").isBlank()
          || text(question, "answer").isBlank()) throw new IllegalArgumentException("导入题目必须包含题干、考点和答案");
    }
    return save("QUESTION_BANK", Map.of("source", "EXCEL_IMPORT", "expectedTotal", questions.size()), questions);
  }

  public Job queueQuestions(UUID blueprintJobId) { return queueQuestions(blueprintJobId, null, "SINGLE_REFILL"); }

  public Job queueQuestions(UUID blueprintJobId, String idempotencyKey) {
    return queueQuestions(blueprintJobId, idempotencyKey, "SINGLE_REFILL");
  }

  public Job queueQuestions(UUID blueprintJobId, String idempotencyKey, String refillPolicy) {
    return queueQuestionsResult(blueprintJobId, idempotencyKey, refillPolicy, "FAST").job();
  }

  public QueueResult queueQuestionsResult(UUID blueprintJobId, String idempotencyKey) {
    return queueQuestionsResult(blueprintJobId, idempotencyKey, "SINGLE_REFILL");
  }

  public QueueResult queueQuestionsResult(UUID blueprintJobId, String idempotencyKey, String refillPolicy) {
    return queueQuestionsResult(blueprintJobId, idempotencyKey, refillPolicy, "FAST");
  }

  public QueueResult queueQuestionsResult(UUID blueprintJobId, String idempotencyKey, String refillPolicy,
      String generationMode) {
    String key = generationProgress.normalizeKey(idempotencyKey);
    var existing = generationProgress.existing(key);
    if (existing.isPresent()) return new QueueResult(getJob(existing.get()), false);
    var blueprint = getJob(blueprintJobId);
    if (!"BLUEPRINT".equals(blueprint.type()) || !"SUCCEEDED".equals(blueprint.status())) {
      throw new IllegalArgumentException("需要一个已完成的细目表任务");
    }
    String policy = normalizeRefillPolicy(refillPolicy);
    String mode = normalizeGenerationMode(generationMode);
    if ("EXPERT".equals(mode) && !refiner.proEnabled()) {
      throw new IllegalStateException("专家命题模式未启用：请配置 APP_AI_QUESTION_PRO_ENABLED=true 后重试");
    }
    var now = Instant.now();
    var job = new Job(UUID.randomUUID(), "QUESTION_BANK", "QUEUED", 0,
        Map.ofEntries(Map.entry("blueprintJobId", blueprintJobId), Map.entry("expectedTotal", blueprint.result().size()),
            Map.entry("batchSize", refiner.batchSize(mode)), Map.entry("retryPolicy", policy), Map.entry("generationMode", mode),
            Map.entry("qualityMode", refiner.enabled() ? "MODEL_GROUNDED" : "RULE_FALLBACK"),
            // Every new bank receives an independent professional discrimination review. The
            // reviewer returns compact scores/flags; only failed slots are sent to repair.
            Map.entry("professionalReviewMode", "REQUIRED"),
            Map.entry("optionReviewMode", professionalReviewer.optionReviewMode()),
            Map.entry("optionRewriteMaxAttempts", optionRewriteMaxAttempts),
             Map.entry("fullRewriteMaxAttempts", fullRewriteMaxAttempts),
             Map.entry("questionQualityVersion", "EXPERT".equals(mode) ? "EXPERT_V3" : "FAST_EXAM_V3"),
             Map.entry("questionEvidenceSnapshots", true),
             Map.entry("reviewOnlyRetry", false),
             Map.entry("questionModelRoute", "EXPERT".equals(mode) ? "QUESTION_MODEL" : "TEXT_MODEL")),
        List.of(), 0, null, now, now);
    jobs.put(job.id(), job);
    persistJob(job);
    try {
      generationProgress.initialize(job.id(), key, blueprint.result().size());
      initializeGenerationSlots(job.id(), blueprint.result());
    } catch (DuplicateKeyException race) {
      jdbc.update("delete from generation_jobs where id=? and idempotency_key is null", job.id());
      jobs.remove(job.id());
      UUID existingId = generationProgress.existing(key)
          .orElseThrow(() -> new IllegalStateException("幂等任务竞争处理失败，请使用同一请求键重试"));
      return new QueueResult(getJob(existingId), false);
    }
    return new QueueResult(job, true);
  }

  public Job executeQuestionJob(UUID id) {
    var queued = getJob(id);
    if (!"QUESTION_BANK".equals(queued.type())) throw new IllegalArgumentException("不是题库生成任务");
    if ("CANCELLED".equals(queued.status())) return queued;
    if (!"QUEUED".equals(queued.status())) return queued;
    int claimed = jdbc.update("update generation_jobs set status='RUNNING',stage_code='SOURCE_RETRIEVING',progress=5,status_message='后台 Worker 已领取任务',updated_at=? where id=? and status='QUEUED'",
        java.sql.Timestamp.from(Instant.now()), id);
    if (claimed != 1) {
      jobs.remove(id);
      return getJob(id);
    }
    Job running = withState(queued, "RUNNING", 5, queued.result(), queued.retryCount(), null);
    storeUpdate(running);
    List<Map<String, Object>> completed = new ArrayList<>();
    try {
      UUID blueprintId = UUID.fromString(String.valueOf(running.request().get("blueprintJobId")));
      var blueprint = getJob(blueprintId);
      UUID knowledgeBaseId = UUID.fromString(String.valueOf(blueprint.request().get("knowledgeBaseId")));
      String generationMode = normalizeGenerationMode(Objects.toString(running.request().get("generationMode"), "FAST"));
      boolean expertMode = "EXPERT".equals(generationMode);
      boolean professionalReviewRequired = "REQUIRED".equals(Objects.toString(
          running.request().get("professionalReviewMode"), ""));
      boolean basicReviewOnly = "BASIC_ONLY".equals(Objects.toString(
           running.request().get("professionalReviewMode"), ""));
      boolean reviewOnlyRetry = Boolean.parseBoolean(String.valueOf(running.request().get("reviewOnlyRetry")));
      List<Map<String, Object>> drafts = new ArrayList<>();
      Map<String, List<RetrievalService.Hit>> evidenceCache = new LinkedHashMap<>();
      generationProgress.update(id, "SOURCE_RETRIEVING", 6, 0, blueprint.result().size(), "正在检索职业标准原文证据", null);
      int retrieved = 0;
      for (Object item : blueprint.result()) {
        if ("CANCELLED".equals(getJob(id).status())) return getJob(id);
        Map<String, Object> row = castMap(item);
        String type = text(row, "type"), point = text(row, "assessmentPoint");
        String evidenceKey = point + "\u0000" + type;
        var hits = evidenceCache.get(evidenceKey);
        if (hits == null) {
          hits = retrieval.search(knowledgeBaseId, retrievalQuery(point, type), 3);
          evidenceCache.put(evidenceKey, hits);
        }
        if (hits.isEmpty()) throw new IllegalStateException("考点未召回任何原文证据：" + point);
        Map<String, Object> draft = draftQuestion(row, type, point, hits);
        var pack = contextAssets.frozenOrBuild(id, sequence(draft), knowledgeBaseId, text(draft, "level"), hits,
            refiner.proContextMaximumCharacters());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("standardEvidence", pack.standardText());
        value.put("contextAssets", pack.contexts());
        draft.put("evidencePack", value);
        drafts.add(draft);
        retrieved++;
        if (retrieved == blueprint.result().size() || retrieved % 10 == 0) {
          int retrievalPercent = 6 + (int) Math.round(14d * retrieved / Math.max(1, blueprint.result().size()));
          generationProgress.update(id, "SOURCE_RETRIEVING", retrievalPercent, retrieved,
              blueprint.result().size(), "正在检索原文证据 " + retrieved + " / " + blueprint.result().size(), null);
        }
      }
      applyQuestionSetGuidance(drafts);
      UUID jobOwnerId = jobOwner(id);
      Map<String, List<Map<String, Object>>> styleMemories = loadStyleMemories(jobOwnerId, drafts);
      for (Map<String, Object> draft : drafts) {
        draft.put("styleMemories", styleMemories.getOrDefault(styleMemoryKey(draft), List.of()));
      }
      initializeGenerationSlots(id, blueprint.result());
      Map<Integer, Map<String, Object>> accepted = loadAcceptedSlots(id);
      Map<Integer, Integer> attempts = loadSlotAttempts(id);
      Map<Integer, String> failureReasons = loadSlotFailures(id);
      Map<Integer, RetryState> retryStates = loadRetryStates(id);
      Map<Integer, String> nextGenerationScopes = loadNextGenerationScopes(id);
      completed.addAll(accepted.values());
      if (reviewOnlyRetry && professionalReviewRequired) {
        resumePendingReviews(id, attempts, accepted, failureReasons, retryStates, nextGenerationScopes);
        completed = accepted.values().stream().sorted(java.util.Comparator.comparingInt(this::sequence)).toList();
      }
      // Delivery policy: full initial generation -> targeted option repair -> one high-quality full rebuild when needed.
      // No rejected candidate can be selected as a fallback.
      Map<Integer, Map<String, Object>> optionRewriteDrafts = new LinkedHashMap<>();
      while (accepted.size() < drafts.size()) {
        if ("CANCELLED".equals(getJob(id).status())) return getJob(id);
        List<Map<String, Object>> pending = drafts.stream()
            .filter(value -> !accepted.containsKey(sequence(value)))
            .filter(value -> canGenerate(retryStates.getOrDefault(sequence(value), RetryState.EMPTY),
                nextGenerationScopes.getOrDefault(sequence(value), "FULL")))
            .sorted(java.util.Comparator.comparingInt((Map<String, Object> value) ->
                attempts.getOrDefault(sequence(value), 0)).thenComparingInt(this::sequence))
            .limit(refiner.batchSize(generationMode) * (expertMode ? Math.min(2, questionConcurrency) : questionConcurrency)).map(value -> {
              int slot = sequence(value);
              String scope = nextGenerationScopes.getOrDefault(slot, "FULL");
              Map<String, Object> retry = "OPTION".equals(scope)
                  ? new LinkedHashMap<>(optionRewriteDrafts.computeIfAbsent(slot, ignored -> optionRewriteDraft(id, slot, value)))
                  : new LinkedHashMap<>(value);
              if (basicReviewOnly) retry.put("basicReviewOnly", true);
              retry.put("recentQuestionSummaries", recentQuestionSummaries(accepted.values(), slot));
              if ("OPTION".equals(scope)) retry.put("optionRewriteOnly", true);
              if ("FULL".equals(scope) && !RetryState.EMPTY.equals(retryStates.getOrDefault(slot, RetryState.EMPTY))) {
                retry.put("fullRewriteOnly", true);
              }
              String reason = failureReasons.get(sequence(value));
              if (reason != null) retry.put("retryFeedback", "上一次未通过原因：" + reason + "。请定向修复，不得改变题型、考点和原文依据。");
              return retry;
            }).toList();
        if (pending.isEmpty()) break;
        int maxAttempt = pending.stream().mapToInt(value -> attempts.getOrDefault(sequence(value), 0) + 1).max().orElse(1);
        String stage = maxAttempt > 1 ? "QUESTION_REFILLING" : "AI_GENERATING";
        int before = accepted.size();
        generationProgress.update(id, stage, Math.min(88, 20 + (int) Math.round(66d * accepted.size() / drafts.size())),
            accepted.size(), drafts.size(), maxAttempt > 1
                ? "正在补生成缺失题位，已通过 " + accepted.size() + " / " + drafts.size()
                : "DeepSeek 正在生成题位 " + pending.stream().map(value -> Integer.toString(sequence(value))).collect(Collectors.joining("、")), null);
        List<QuestionRefinementService.Candidate> candidates = generateCandidatesInParallel(pending, generationMode);
        Map<Integer, QuestionProfessionalReviewService.Review> professionalReviews = Map.of();
        if (professionalReviewRequired) {
          generationProgress.update(id, "PROFESSIONAL_REVIEWING",
              Math.min(90, 21 + (int) Math.round(66d * accepted.size() / drafts.size())), accepted.size(), drafts.size(),
              "正在由独立职业考核审题器检查题目区分度", null);
          professionalReviews = reviewCandidatesInParallel(candidates);
        }
        for (QuestionRefinementService.Candidate candidate : candidates) {
          Map<String, Object> question = new LinkedHashMap<>(candidate.question());
          int slot = sequence(question);
          String generationScope = Boolean.parseBoolean(String.valueOf(question.get("optionRewriteOnly"))) ? "OPTION" : "FULL";
          int attempt = attempts.getOrDefault(slot, 0) + 1;
          attempts.put(slot, attempt);
          saveProGenerationDesign(id, slot, running.retryCount() * 1_000 + attempt, question);
          RetryState retryState = retryStates.getOrDefault(slot, RetryState.EMPTY)
              .after(generationScope, attempt > 1);
          retryStates.put(slot, retryState);
          QuestionProfessionalReviewService.Review professionalReview = professionalReviewRequired
              ? professionalReviews.getOrDefault(slot, new QuestionProfessionalReviewService.Review(slot, false, 0,
                  List.of("PROFESSIONAL_REVIEW_UNAVAILABLE"), "专业审题未返回该题结果", List.of(), false))
              : new QuestionProfessionalReviewService.Review(slot, true, 100, 0, List.of("BASIC_REVIEW_ONLY"),
                  "当前任务仅执行基础审查", List.of());
          if (professionalReviewRequired) {
            // Keep every worker retry auditable even though generation_job_items attempts restart from one.
            professionalReviewer.save(id, slot, running.retryCount() * 1_000 + attempt, professionalReview, professionalDesign(question));
          }
          // A reviewer outage is an infrastructure event. Preserve this candidate for manual or
          // explicit review retry instead of regenerating an otherwise valid question.
          if (professionalReviewRequired && candidate.valid() && !professionalReview.available()) {
            String reviewError = "专业审题服务异常，候选题已保留，未触发重新出题："
                + Objects.toString(professionalReview.feedback(), "专业审题服务不可用").substring(0,
                    Math.min(260, Objects.toString(professionalReview.feedback(), "专业审题服务不可用").length()));
            failureReasons.put(slot, reviewError);
            nextGenerationScopes.remove(slot);
            retryStates.put(slot, new RetryState(fullRewriteMaxAttempts, optionRewriteMaxAttempts));
            updateGenerationSlot(id, slot, "REVIEW_PENDING", attempt,
                refiner.deliveryQuestion(question), "REVIEW_SERVICE_ERROR", reviewError,
                candidate.qualityScore(), false, false, generationScope, null);
            continue;
          }
          String reason = candidate.failureReason();
          if (reason == null && !professionalReview.passed()) reason = professionalReviewFeedback(professionalReview);
          if (candidate.valid() && reason == null) {
            question = refiner.deliveryQuestion(question);
            question.put("qualityScore", candidate.qualityScore());
            question.put("professionalReviewScore", professionalReview.discriminationScore());
            accepted.put(slot, question);
            failureReasons.remove(slot);
            optionRewriteDrafts.remove(slot);
            nextGenerationScopes.remove(slot);
            updateGenerationSlot(id, slot, "ACCEPTED", attempt, question, null, null,
                candidate.qualityScore(), candidate.hardValid(), false, generationScope, null);
          } else {
            String candidateReason = Objects.toString(reason, "内容质量门禁未通过");
            String nextScope = nextGenerationScope(candidate, professionalReview, retryState);
            String finalReason = nextScope == null
                ? "达到题位重试上限仍未通过职业质量门禁：" + candidateReason : candidateReason;
            failureReasons.put(slot, finalReason);
            if ("OPTION".equals(nextScope)) {
              Map<String, Object> optionRewrite = new LinkedHashMap<>(question);
              optionRewrite.put("optionRewriteOnly", true);
              optionRewriteDrafts.put(slot, optionRewrite);
            } else {
              optionRewriteDrafts.remove(slot);
            }
            if (nextScope == null) nextGenerationScopes.remove(slot); else nextGenerationScopes.put(slot, nextScope);
            Map<String, Object> auditSafeQuestion = refiner.deliveryQuestion(question);
            updateGenerationSlot(id, slot, "RETRYING", attempt,
                auditSafeQuestion, "QUALITY_REJECTED", finalReason, candidate.qualityScore(),
                false, false, generationScope, nextScope);
            if (nextScope == null && terminalModelFailure(candidateReason)) throw new IllegalStateException(candidateReason);
          }
        }
        completed = accepted.values().stream().sorted(java.util.Comparator.comparingInt(this::sequence)).toList();
        int progressValue = Math.min(90, 20 + (int) Math.round(70d * accepted.size() / drafts.size()));
        running = withState(running, "RUNNING", progressValue, List.copyOf(completed), running.retryCount(), null);
        storeUpdate(running);
        if (accepted.size() == before) {
          generationProgress.update(id, "QUESTION_REFILLING", Math.min(90, progressValue), accepted.size(), drafts.size(),
              "本轮题目未通过门禁，正在按反馈自动重生成（已尝试 " + maxAttempt + " 轮，可随时取消）", null);
        }
      }
      if ("CANCELLED".equals(getJob(id).status())) return getJob(id);
      generationProgress.update(id, "STRUCTURE_VALIDATING", 93, completed.size(), drafts.size(), "正在校验九题型结构和答案格式", null);
      if ("CANCELLED".equals(getJob(id).status())) return getJob(id);
      generationProgress.update(id, "QUALITY_SCORING", 97, completed.size(), drafts.size(), "正在记录质量评分，评分不影响入库", null);
      int reviewPending = jdbc.queryForObject("select count(*) from generation_job_items where job_id=? and status='REVIEW_PENDING'",
          Integer.class, id);
      if (completed.size() != drafts.size()) {
        String message = "计划 " + drafts.size() + " 道，题型结构校验后保留 " + completed.size()
            + " 道，缺少 " + (drafts.size() - completed.size()) + " 道；"
            + (reviewPending > 0 ? "其中 " + reviewPending + " 道等待专业审题服务恢复，未重复生成；" : "")
            + "已保留合格题并允许不完整导出";
        String errorCode = reviewPending > 0 ? "REVIEW_SERVICE_ERROR" : "QUESTION_COUNT_MISMATCH";
        Job partial = withState(running, "FAILED", 100, List.copyOf(completed), running.retryCount() + 1, message);
        storeUpdate(partial);
        generationProgress.update(id, reviewPending > 0 ? "REVIEW_PENDING" : "QUESTION_COUNT_MISMATCH", 100,
            completed.size(), drafts.size(), message, errorCode);
        return partial;
      }
      Job done = withState(running, "SUCCEEDED", 100, List.copyOf(completed), running.retryCount(), null);
      storeUpdate(done);
      generationProgress.update(id, "SUCCEEDED", 100, completed.size(), drafts.size(), "AI 题库生成并通过交付门禁", null);
      return done;
    } catch (Exception e) {
      if ("CANCELLED".equals(getJob(id).status())) return getJob(id);
      Job failed = withState(running, "FAILED", 100, List.copyOf(completed), running.retryCount() + 1, limit(e.getMessage()));
      storeUpdate(failed);
      String errorCode = classifyGenerationError(e);
      generationProgress.update(id, errorCode, 100, completed.size(), expectedItems(running),
          "AI 命题任务失败", errorCode);
      throw e;
    }
  }

  public Job retry(UUID id) {
    var previous = getJob(id);
    if (!"FAILED".equals(previous.status())) throw new IllegalArgumentException("仅失败任务可重试");
    String errorCode = jdbc.queryForObject("select error_code from generation_jobs where id=?", String.class, id);
    boolean reviewOnly = "REVIEW_SERVICE_ERROR".equals(errorCode);
    if ("QUESTION_COUNT_MISMATCH".equals(errorCode)) {
      jdbc.update("update generation_job_items set status='PLANNED',attempts=0,question_json=null,error_code=null,error_message=null,candidate_history_json='[]',best_score=0,fallback_selected=false,updated_at=? where job_id=? and status<>'ACCEPTED'",
          java.sql.Timestamp.from(Instant.now()), id);
    }
    Map<String, Object> retryRequest = new LinkedHashMap<>(previous.request());
    retryRequest.put("reviewOnlyRetry", reviewOnly);
    Job queued = new Job(previous.id(), previous.type(), "QUEUED", 0, retryRequest,
        "QUESTION_COUNT_MISMATCH".equals(errorCode) ? previous.result() : List.of(),
        previous.retryCount(), null, previous.createdAt(), Instant.now());
    storeUpdate(queued);
    generationProgress.update(id, "GENERATION_QUEUED", 0, 0, expectedItems(previous), "失败任务已重新排队", null);
    return queued;
  }

  public List<UUID> recoverableJobs() {
    jdbc.update("update generation_jobs set status='QUEUED',stage_code='GENERATION_QUEUED',status_message='服务恢复后重新排队',updated_at=? where status='RUNNING' and job_type='QUESTION_BANK'",
        java.sql.Timestamp.from(Instant.now()));
    List<UUID> ids = jdbc.query("select id from generation_jobs where status='QUEUED' and job_type='QUESTION_BANK'",
        (rs, n) -> UUID.fromString(rs.getString(1)));
    ids.forEach(jobs::remove);
    return ids;
  }

  public List<Job> listJobs(int limit) {
    int safe = Math.max(1, Math.min(limit, 100));
    String sql = "select id from generation_jobs " + (access.admin() ? "" : "where owner_id=? ")
        + "order by updated_at desc limit ?";
    List<UUID> ids = access.admin()
        ? jdbc.query(sql, (rs, n) -> UUID.fromString(rs.getString(1)), safe)
        : jdbc.query(sql, (rs, n) -> UUID.fromString(rs.getString(1)), access.currentUserId(), safe);
    return ids.stream().map(this::getJob).toList();
  }

  public Job cancel(UUID id) {
    var job = getJob(id);
    if (!Set.of("QUEUED", "RUNNING").contains(job.status())) throw new IllegalArgumentException("仅排队或执行中的任务可以取消");
    var cancelled = withState(job, "CANCELLED", job.progress(), job.result(), job.retryCount(), "已由用户取消");
    storeUpdate(cancelled);
    generationProgress.update(id, "CANCELLED", job.progress(), job.result().size(), expectedItems(job), "任务已由用户取消", "CANCELLED");
    return cancelled;
  }

  public Job getJob(UUID id) {
    access.assertJob(id);
    var value = jobs.get(id);
    if (value != null) return value;
    var rows = jdbc.query("select job_type,status,progress,request_json,result_json,retry_count,error_message,created_at,updated_at from generation_jobs where id=?",
        (rs, n) -> new StoredJob(rs.getString("job_type"), rs.getString("status"), rs.getInt("progress"),
            rs.getString("request_json"), rs.getString("result_json"), rs.getInt("retry_count"),
            rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()), id);
    if (rows.isEmpty()) throw new IllegalArgumentException("任务不存在");
    try {
      var row = rows.getFirst();
      value = new Job(id, row.type(), row.status(), row.progress(),
          json.readValue(row.request(), new TypeReference<>() { }),
          row.result() == null ? List.of() : json.readValue(row.result(), new TypeReference<>() { }),
          row.retryCount(), row.errorMessage(), row.createdAt(), row.updatedAt());
      jobs.put(id, value);
      return value;
    } catch (org.springframework.security.access.AccessDeniedException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("任务数据损坏", e);
    }
  }

  public byte[] workbook(UUID jobId) throws Exception {
    return workbook(jobId, (processed, total) -> { });
  }

  public byte[] workbook(UUID jobId, ExportProgress progress) throws Exception {
    var job = getJob(jobId);
    String errorCode = jdbc.queryForObject("select error_code from generation_jobs where id=?", String.class, jobId);
    boolean partial = "FAILED".equals(job.status()) && "QUESTION_COUNT_MISMATCH".equals(errorCode)
        && !job.result().isEmpty();
    if (!"QUESTION_BANK".equals(job.type()) || (!("SUCCEEDED".equals(job.status()) || partial))) {
      throw new IllegalArgumentException("只能导出完整题库，或因数量不足失败但仍保留合格题目的任务");
    }
    try (var workbook = new XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("题库");
      String[] heads = {"序号", "题型", "原始题型", "题型适配说明", "职业层级", "能力目标", "难度", "难度依据", "考点", "题干", "选项", "答案", "解析", "评分规则", "质量分", "原文定位", "原文片段"};
      Row header = sheet.createRow(0);
      for (int i = 0; i < heads.length; i++) header.createCell(i).setCellValue(heads[i]);
      int rowIndex = 1;
      for (Object item : job.result()) {
        Map<String, Object> question = castMap(item);
        Row row = sheet.createRow(rowIndex++);
        String[] data = {text(question, "sequence"), text(question, "type"), text(question, "originalType"),
            text(question, "typeAdaptation"), text(question, "level"), text(question, "abilityObjective"),
            text(question, "difficulty"), text(question, "difficultyRationale"), text(question, "assessmentPoint"),
            text(question, "stem"), text(question, "options"), text(question, "answer"), text(question, "analysis"),
            text(question, "scoringRubric"), text(question, "qualityScore"), text(question, "sourceRef"),
            text(question, "sourceExcerpt")};
        for (int i = 0; i < data.length; i++) row.createCell(i).setCellValue(data[i]);
        progress.onProgress(rowIndex - 1, job.result().size());
      }
      for (int i = 0; i < heads.length; i++) sheet.autoSizeColumn(i);
      Sheet delivery = workbook.createSheet("交付说明");
      String[][] summary = {
          {"生成任务", job.id().toString()}, {"交付状态", partial ? "失败—数量不足，允许部分交付" : "成功—完整交付"},
          {"状态码", partial ? "QUESTION_COUNT_MISMATCH" : "SUCCEEDED"},
          {"计划数量", Integer.toString(expectedItems(job))}, {"实际数量", Integer.toString(job.result().size())},
          {"缺失数量", Integer.toString(Math.max(0, expectedItems(job) - job.result().size()))},
          {"说明", partial ? "本文件不是完整题库，请结合质量报告查看缺失题位后继续补题。" : "题目数量与计划一致。"}
      };
      for (int i = 0; i < summary.length; i++) {
        Row row = delivery.createRow(i);
        row.createCell(0).setCellValue(summary[i][0]); row.createCell(1).setCellValue(summary[i][1]);
      }
      delivery.autoSizeColumn(0); delivery.setColumnWidth(1, 15000);
      workbook.write(out);
      return out.toByteArray();
    }
  }

  public boolean partialExport(UUID jobId) {
    Job job = getJob(jobId);
    String code = jdbc.queryForObject("select error_code from generation_jobs where id=?", String.class, jobId);
    return "FAILED".equals(job.status()) && "QUESTION_COUNT_MISMATCH".equals(code) && !job.result().isEmpty();
  }

  public int expectedTotal(UUID jobId) { return expectedItems(getJob(jobId)); }

  /** Applies a human-reviewed question snapshot to both the job artifact and the resumable slot. */
  public Map<String, Object> updateQuestion(UUID jobId, int sequence, Map<String, Object> patch) {
    Job job = getJob(jobId);
    if (!"QUESTION_BANK".equals(job.type())) throw new IllegalArgumentException("只能编辑题库任务");
    List<Map<String, Object>> updated = new ArrayList<>(); Map<String, Object> saved = null;
    for (Map<String, Object> original : job.result()) {
      if (this.sequence(original) != sequence) { updated.add(original); continue; }
      Map<String, Object> value = new LinkedHashMap<>(original);
      for (String field : List.of("stem", "options", "answer", "analysis", "scoringRubric", "difficulty",
          "assessmentPoint", "sourceRef", "sourceExcerpt")) {
        if (patch != null && patch.containsKey(field)) value.put(field, Objects.toString(patch.get(field), "").trim());
      }
      if (text(value, "stem").length() < 8 || text(value, "answer").isBlank() || text(value, "analysis").isBlank()) {
        throw new IllegalArgumentException("题干、答案和解析必须完整");
      }
      value.put("humanEdited", true); value.put("humanEditedAt", Instant.now().toString());
      saved = value; updated.add(value);
    }
    if (saved == null) throw new IllegalArgumentException("题目序号不存在");
    Job next = withState(job, job.status(), job.progress(), List.copyOf(updated), job.retryCount(), job.errorMessage());
    storeUpdate(next);
    try { jdbc.update("update generation_job_items set question_json=?,updated_at=? where job_id=? and sequence_no=?",
        json.writeValueAsString(saved), java.sql.Timestamp.from(Instant.now()), jobId, sequence); }
    catch (Exception error) { throw new IllegalStateException("保存人工编辑题目失败", error); }
    saveStyleMemory(jobId, saved);
    return saved;
  }

  /** Stores editing-derived design signals, never a full question, for later prompt inspiration. */
  private void saveStyleMemory(UUID jobId, Map<String, Object> question) {
    try {
      Map<String, Object> signal = new LinkedHashMap<>();
      signal.put("assessmentFocus", shorten(text(question, "assessmentPoint"), 180));
      signal.put("questionShape", text(question, "type"));
      signal.put("stemStyle", shorten(text(question, "stem").replaceAll("[。！？].*", ""), 100));
      signal.put("guidance", "保持岗位判断、条件信息与可执行处置的自然表达；只借鉴设计方向，不复用题干、选项或答案。");
      UUID owner = jobOwner(jobId);
      jdbc.update("insert into question_style_memories(id,owner_id,level_name,question_type,signal_json,created_at,updated_at) values(?,?,?,?,?,?,?)",
          UUID.randomUUID(), owner, text(question, "level"), text(question, "type"), json.writeValueAsString(signal),
          java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
    } catch (Exception error) {
      throw new IllegalStateException("保存命题经验失败", error);
    }
  }

  private Map<String, Object> draftQuestion(Map<String, Object> row, String type, String point,
      List<RetrievalService.Hit> hits) {
    String evidence = hits.stream().map(hit -> "【" + hit.sourceRef() + "】\n"
        + hit.content().substring(0, Math.min(900, hit.content().length())))
        .collect(Collectors.joining("\n\n"));
    if (evidence.length() > 1800) evidence = evidence.substring(0, 1800);
    boolean procedureEvidence = hasProcedureEvidence(evidence);
    boolean mappingEvidence = hasMappingEvidence(evidence);
    String effectiveType = type;
    String typeAdaptation = "";
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("sequence", row.get("sequence"));
    draft.put("type", effectiveType);
    draft.put("originalType", type);
    draft.put("typeAdaptation", typeAdaptation);
    draft.put("assessmentPoint", point);
    draft.put("difficulty", row.get("difficulty"));
    draft.put("level", row.get("level"));
    draft.put("cognitiveTarget", row.get("cognitiveTarget"));
    draft.put("abilityObjective", row.get("abilityObjective"));
    draft.put("difficultyRationale", row.get("difficultyRationale"));
    draft.put("procedureEvidence", procedureEvidence);
    draft.put("mappingEvidence", mappingEvidence);
    // A template stem/answer can accidentally pass a structural gate when a model response is malformed.
    // Leave answer-bearing fields blank so only a model-generated professional scenario is deliverable.
    draft.put("stem", "");
    draft.put("options", "");
    draft.put("answer", "");
    draft.put("analysis", "");
    draft.put("scoringRubric", "");
    draft.put("sourceRef", hits.stream().map(RetrievalService.Hit::sourceRef).distinct().collect(Collectors.joining("；")));
    draft.put("sourceExcerpt", evidence);
    draft.put("retrievalScore", Math.round(hits.getFirst().score() * 10_000d) / 10_000d);
    draft.put("evidenceChunkIds", hits.stream().map(hit -> hit.chunkId().toString()).toList());
    return draft;
  }

  /** A deterministic set plan gives each slot a different angle without adding a model call or a gate. */
  private void applyQuestionSetGuidance(List<Map<String, Object>> drafts) {
    List<String> lenses = List.of("岗位准备与资源安排", "现场执行与关键核验", "异常识别与处置",
        "复核、记录与改进", "职责协同与质量管理");
    for (int index = 0; index < drafts.size(); index++) {
      Map<String, Object> draft = drafts.get(index);
      Map<String, Object> guidance = new LinkedHashMap<>();
      guidance.put("scenarioLens", lenses.get(index % lenses.size()));
      guidance.put("setIntent", "围绕当前考点选择与其他题不同的岗位判断角度，避免复用相同题干开头和处置路径");
      guidance.put("avoidOverlapWith", drafts.stream().limit(index).map(value -> text(value, "assessmentPoint"))
          .filter(value -> !value.isBlank()).limit(4).toList());
      draft.put("setGuidance", guidance);
    }
  }

  private UUID jobOwner(UUID jobId) {
    UUID owner = jdbc.queryForObject("select owner_id from generation_jobs where id=?", UUID.class, jobId);
    if (owner == null) throw new IllegalStateException("命题任务缺少所属用户");
    return owner;
  }

  private String styleMemoryKey(Map<String, Object> value) { return text(value, "level") + "\u0000" + text(value, "type"); }

  private Map<String, List<Map<String, Object>>> loadStyleMemories(UUID ownerId, List<Map<String, Object>> drafts) {
    Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
    for (Map<String, Object> draft : drafts) {
      String key = styleMemoryKey(draft);
      if (result.containsKey(key)) continue;
      List<Map<String, Object>> memories = jdbc.query("select signal_json from question_style_memories where owner_id=? and level_name=? and question_type=? order by updated_at desc limit 3",
          (rs, row) -> jsonMap(rs.getString(1)), ownerId, text(draft, "level"), text(draft, "type"));
      result.put(key, memories.stream().filter(value -> !value.isEmpty()).toList());
    }
    return result;
  }

  private List<Map<String, Object>> recentQuestionSummaries(java.util.Collection<Map<String, Object>> questions, int currentSlot) {
    return questions.stream().filter(value -> sequence(value) != currentSlot)
        .sorted(java.util.Comparator.comparingInt(this::sequence).reversed()).limit(4).map(value -> Map.<String, Object>of(
            "assessmentPoint", shorten(text(value, "assessmentPoint"), 140),
            "stem", shorten(text(value, "stem"), 120),
            "type", text(value, "type"))).toList();
  }

  private String shorten(String value, int maximum) {
    String safe = Objects.toString(value, "").trim();
    return safe.substring(0, Math.min(safe.length(), maximum));
  }

  /** A standard's bullet ordering is not a workflow. Only explicit procedure wording enables ordering items. */
  private boolean hasProcedureEvidence(String evidence) {
    String normalized = evidence.replaceAll("\\s+", "");
    boolean orderedWords = normalized.matches("(?s).*(首先|然后|随后|最后|依次|步骤|工序|先.*后).*" );
    boolean numberedSteps = normalized.matches("(?s).*(?:步骤|工序).{0,60}(?:1[.、]|一、).*(?:2[.、]|二、).*" );
    return orderedWords && numberedSteps;
  }

  private boolean hasMappingEvidence(String evidence) {
    String normalized = evidence.replaceAll("\\s+", "");
    int pairs = 0;
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:对应|分别为|匹配|关系为)").matcher(normalized);
    while (matcher.find()) pairs++;
    return pairs >= 2;
  }

  private String firstFact(String content) {
    String clean = content.replaceAll("\\[第\\d+页]", "").replaceAll("\\s+", " ").trim();
    for (String sentence : clean.split("(?<=[。；])")) {
      String value = sentence.trim();
      if (value.length() >= 24) return value.substring(0, Math.min(value.length(), 180));
    }
    return clean.substring(0, Math.min(clean.length(), 180));
  }

  private void initializeGenerationSlots(UUID jobId, List<Map<String, Object>> plan) {
    Instant now = Instant.now();
    for (Map<String, Object> row : plan) {
      int slot = sequence(row);
      Integer count = jdbc.queryForObject("select count(*) from generation_job_items where job_id=? and sequence_no=?",
          Integer.class, jobId, slot);
      if (count != null && count > 0) continue;
      try {
        jdbc.update("insert into generation_job_items(id,job_id,sequence_no,question_type,difficulty,assessment_point,status,attempts,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), jobId, slot, text(row, "type"), text(row, "difficulty"),
            text(row, "assessmentPoint"), "PLANNED", 0, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
      } catch (DuplicateKeyException ignored) { }
    }
  }

  private List<QuestionRefinementService.Candidate> generateCandidatesInParallel(List<Map<String, Object>> pending,
      String generationMode) {
    List<CompletableFuture<List<QuestionRefinementService.Candidate>>> futures = new ArrayList<>();
    Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
    for (Map<String, Object> draft : pending) {
      String scope = Boolean.parseBoolean(String.valueOf(draft.get("optionRewriteOnly"))) ? "OPTION" : "FULL";
      String key = text(draft, "type") + "\u0000" + text(draft, "difficulty") + "\u0000" + text(draft, "level")
          + "\u0000" + scope + "\u0000" + Boolean.parseBoolean(String.valueOf(draft.get("fullRewriteOnly")));
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(draft);
    }
    for (List<Map<String, Object>> group : groups.values()) {
      int batchSize = refiner.batchSize(generationMode);
      for (int index = 0; index < group.size(); index += batchSize) {
        List<Map<String, Object>> batch = group.subList(index, Math.min(index + batchSize, group.size()));
        futures.add(CompletableFuture.supplyAsync(() -> refiner.generateCandidates(batch, generationMode), questionGenerationExecutor));
      }
    }
    return futures.stream().flatMap(future -> future.join().stream())
        .sorted(java.util.Comparator.comparingInt(candidate -> sequence(candidate.question()))).toList();
  }

  /** Review only structurally and locally valid candidates; invalid drafts already carry precise retry feedback. */
  private Map<Integer, QuestionProfessionalReviewService.Review> reviewCandidatesInParallel(
      List<QuestionRefinementService.Candidate> candidates) {
    List<Map<String, Object>> eligible = candidates.stream().filter(QuestionRefinementService.Candidate::valid)
        .map(QuestionRefinementService.Candidate::question).toList();
    if (eligible.isEmpty()) return Map.of();
    List<CompletableFuture<List<QuestionProfessionalReviewService.Review>>> futures = new ArrayList<>();
    for (int index = 0; index < eligible.size(); index += professionalReviewer.batchSize()) {
      List<Map<String, Object>> batch = eligible.subList(index,
          Math.min(index + professionalReviewer.batchSize(), eligible.size()));
      futures.add(CompletableFuture.supplyAsync(() -> professionalReviewer.review(batch), questionGenerationExecutor));
    }
    Map<Integer, QuestionProfessionalReviewService.Review> result = new LinkedHashMap<>();
    for (CompletableFuture<List<QuestionProfessionalReviewService.Review>> future : futures) {
      for (QuestionProfessionalReviewService.Review review : future.join()) result.put(review.sequence(), review);
    }
    return result;
  }

  /** Rechecks preserved candidates after a reviewer outage without spending another generation call. */
  private void resumePendingReviews(UUID jobId, Map<Integer, Integer> attempts,
      Map<Integer, Map<String, Object>> accepted, Map<Integer, String> failureReasons,
      Map<Integer, RetryState> retryStates, Map<Integer, String> nextGenerationScopes) {
    List<Map<String, Object>> pending = loadReviewPendingCandidates(jobId);
    if (pending.isEmpty()) return;
    generationProgress.update(jobId, "PROFESSIONAL_REVIEWING", 90, accepted.size(),
        expectedItems(getJob(jobId)), "正在重新审查上次保留的候选题", null);
    Map<Integer, QuestionProfessionalReviewService.Review> reviews = professionalReviewer.review(pending).stream()
        .collect(Collectors.toMap(QuestionProfessionalReviewService.Review::sequence, value -> value,
            (left, right) -> right, LinkedHashMap::new));
    int retryOrdinal = getJob(jobId).retryCount();
    for (Map<String, Object> question : pending) {
      int slot = sequence(question);
      int attempt = attempts.getOrDefault(slot, 0);
      QuestionProfessionalReviewService.Review review = reviews.get(slot);
      if (review == null) review = new QuestionProfessionalReviewService.Review(slot, false, 0,
          List.of("PROFESSIONAL_REVIEW_UNAVAILABLE"), "专业审题未返回该题结果", List.of(), false);
      professionalReviewer.save(jobId, slot, retryOrdinal * 1_000 + attempt, review, professionalDesign(question));
      if (!review.available()) continue;
      if (review.passed()) {
        Map<String, Object> delivery = refiner.deliveryQuestion(question);
        Integer storedScore = jdbc.queryForObject("select best_score from generation_job_items where job_id=? and sequence_no=?",
            Integer.class, jobId, slot);
        delivery.put("qualityScore", storedScore == null ? 0 : storedScore);
        delivery.put("professionalReviewScore", review.discriminationScore());
        accepted.put(slot, delivery);
        failureReasons.remove(slot);
        nextGenerationScopes.remove(slot);
        retryStates.put(slot, new RetryState(fullRewriteMaxAttempts, optionRewriteMaxAttempts));
        updateGenerationSlot(jobId, slot, "ACCEPTED", attempt, delivery, null, null,
            storedScore == null ? 0 : storedScore,
            false, false, "FULL", null);
      } else {
        String reason = professionalReviewFeedback(review);
        failureReasons.put(slot, reason);
        retryStates.put(slot, RetryState.EMPTY);
        nextGenerationScopes.put(slot, "FULL");
        updateGenerationSlot(jobId, slot, "RETRYING", attempt, refiner.deliveryQuestion(question),
            "QUALITY_REJECTED", reason, 0, false, false, "FULL", "FULL");
      }
    }
  }

  private List<Map<String, Object>> loadReviewPendingCandidates(UUID jobId) {
    return jdbc.query("select question_json from generation_job_items where job_id=? and status='REVIEW_PENDING' and question_json is not null order by sequence_no",
        (rs, row) -> {
          try { return json.readValue(rs.getString(1), new TypeReference<Map<String, Object>>() { }); }
          catch (Exception error) { throw new IllegalStateException("待审题位数据损坏", error); }
        }, jobId);
  }

  private String professionalReviewFeedback(QuestionProfessionalReviewService.Review review) {
    String flags = review.flags().isEmpty() ? "专业区分度不足" : String.join("、", review.flags());
    String optionFeedback = review.optionReviews().stream().filter(option -> !option.passed())
        .map(option -> option.option() + "项(" + option.misconceptionType() + ")：" + option.feedback())
        .collect(Collectors.joining("；"));
    String outsider = review.outsiderSolvableScore() > 0
        ? "；行业外可解性=" + review.outsiderSolvableScore() : "";
    return limit("专业审题未通过[" + flags + "]：" + review.feedback() + outsider
        + (optionFeedback.isBlank() ? "" : "；逐项修复：" + optionFeedback));
  }

  /** A good stem/answer is retained when the independent reviewer identifies only distractor weakness. */
  private boolean optionRewriteEligible(QuestionRefinementService.Candidate candidate,
      QuestionProfessionalReviewService.Review review) {
    if (!candidate.valid() || !Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE").contains(text(candidate.question(), "type"))) return false;
    Set<String> optionOnly = Set.of("WEAK_DISTRACTOR", "OUTSIDER_SOLVABLE", "OPTION_DIMENSION_MISMATCH",
        "CORRECT_OPTION_SIGNATURE", "IMPLAUSIBLE_DISTRACTOR");
    return !review.flags().isEmpty() && review.flags().stream().allMatch(optionOnly::contains);
  }

  private boolean canGenerate(RetryState state, String scope) {
    return "OPTION".equals(scope) ? state.optionAttempts() < optionRewriteMaxAttempts
        : state.fullAttempts() < fullRewriteMaxAttempts;
  }

  private String nextGenerationScope(QuestionRefinementService.Candidate candidate,
      QuestionProfessionalReviewService.Review review, RetryState state) {
    if (optionRewriteEligible(candidate, review) && state.optionAttempts() < optionRewriteMaxAttempts) return "OPTION";
    return state.fullAttempts() < fullRewriteMaxAttempts ? "FULL" : null;
  }

  /** Restores a targeted option-rewrite draft after a worker restart without exposing its design card. */
  private Map<String, Object> optionRewriteDraft(UUID jobId, int slot, Map<String, Object> original) {
    Map<String, Object> result = new LinkedHashMap<>(original);
    List<Map<String, Object>> history = loadCandidateHistory(jobId, slot);
    history.stream().max(java.util.Comparator.comparingInt(value -> asNumber(value.get("attempt"), 0))).ifPresent(snapshot -> {
      Object question = snapshot.get("question");
      if (question instanceof Map<?, ?> values) {
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
      }
    });
    List<String> designs = jdbc.query("select design_json from question_professional_audits where job_id=? and sequence_no=? and stage='AI_PROFESSIONAL_REVIEW' order by generation_attempt desc,updated_at desc limit 1",
        (rs, row) -> rs.getString(1), jobId, slot);
    if (!designs.isEmpty() && designs.getFirst() != null && !designs.getFirst().isBlank()) {
      try { result.put("_professionalDesign", json.readValue(designs.getFirst(), new TypeReference<>() { })); }
      catch (Exception error) { throw new IllegalStateException("选项重写设计卡损坏", error); }
    }
    return result;
  }

  private Map<String, Object> professionalDesign(Map<String, Object> question) {
    Object raw = question.get("_professionalDesign");
    if (!(raw instanceof Map<?, ?> map)) return Map.of();
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  /** Persists the two Pro stages without retaining provider reasoning content. */
  private void saveProGenerationDesign(UUID jobId, int sequence, int attempt, Map<String, Object> question) {
    Map<String, Object> plan = mapValue(question.get("_proGenerationPlan"));
    if (plan.isEmpty()) return;
    Map<String, Object> metadata = mapValue(question.get("_proGenerationMetadata"));
    String model = text(metadata, "model");
    if (model.isBlank()) model = "QUESTION_MODEL";
    String designEffort = text(metadata, "designEffort");
    if (designEffort.isBlank()) designEffort = "high";
    String writeEffort = text(metadata, "writeEffort");
    if (writeEffort.isBlank()) writeEffort = "high";
    Map<String, Object> validation = new LinkedHashMap<>();
    validation.put("contextSourceCount", metadata.getOrDefault("contextSourceCount", 0));
    validation.put("sequence", sequence);
    validation.put("designPresent", true);
    validation.put("pipelineVersion", metadata.getOrDefault("pipelineVersion", "QUESTION_PIPELINE_V2"));
    saveProDesignStage(jobId, sequence, attempt, "PRO_DESIGN", model, designEffort, plan, validation,
        asNumber(metadata.get("designDurationMs"), 0));
    saveProDesignStage(jobId, sequence, attempt, "PRO_WRITE", model, writeEffort, professionalDesign(question), validation,
        asNumber(metadata.get("writeDurationMs"), 0));
  }

  private void saveProDesignStage(UUID jobId, int sequence, int attempt, String stage, String model, String effort,
      Map<String, Object> design, Map<String, Object> validation, long durationMs) {
    try {
      Timestamp now = Timestamp.from(Instant.now());
      String designJson = json.writeValueAsString(design);
      String validationJson = json.writeValueAsString(validation);
      int updated = jdbc.update("update question_generation_designs set model=?,reasoning_effort=?,design_json=?,validation_json=?,duration_ms=?,created_at=? where job_id=? and sequence_no=? and generation_attempt=? and stage=?",
          model, effort, designJson, validationJson, Math.max(0, durationMs), now, jobId, sequence, attempt, stage);
      if (updated == 0) {
        try {
          jdbc.update("insert into question_generation_designs(id,job_id,sequence_no,generation_attempt,stage,model,reasoning_effort,design_json,validation_json,duration_ms,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",
              UUID.randomUUID(), jobId, sequence, attempt, stage, model, effort, designJson, validationJson,
              Math.max(0, durationMs), now);
        } catch (DuplicateKeyException raced) {
          jdbc.update("update question_generation_designs set model=?,reasoning_effort=?,design_json=?,validation_json=?,duration_ms=?,created_at=? where job_id=? and sequence_no=? and generation_attempt=? and stage=?",
              model, effort, designJson, validationJson, Math.max(0, durationMs), now, jobId, sequence, attempt, stage);
        }
      }
    } catch (Exception error) {
      throw new IllegalStateException("保存深度命题设计卡失败", error);
    }
  }

  public List<QuestionGenerationDesign> generationDesigns(UUID jobId) {
    access.assertJob(jobId);
    return jdbc.query("select sequence_no,generation_attempt,stage,model,reasoning_effort,design_json,validation_json,duration_ms,created_at from question_generation_designs where job_id=? order by sequence_no,generation_attempt desc,stage",
        (rs, row) -> new QuestionGenerationDesign(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4),
            rs.getString(5), jsonMap(rs.getString(6)), jsonMap(rs.getString(7)), rs.getLong(8),
            rs.getTimestamp(9).toInstant()), jobId);
  }

  public List<QuestionContextAssetService.EvidenceSnapshot> evidenceSnapshots(UUID jobId) {
    getJob(jobId);
    return contextAssets.snapshots(jobId);
  }

  private Map<String, Object> mapValue(Object raw) {
    if (!(raw instanceof Map<?, ?> source)) return Map.of();
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  private Map<String, Object> jsonMap(String raw) {
    try { return json.readValue(Objects.toString(raw, "{}"), new TypeReference<>() { }); }
    catch (Exception ignored) { return Map.of(); }
  }

  private void updateGenerationSlot(UUID jobId, int slot, String status, int attempts,
      Map<String, Object> question, String errorCode, String errorMessage, int qualityScore,
      boolean fallbackEligible, boolean fallbackSelected, String generationScope, String nextGenerationScope) {
    try {
      List<Map<String, Object>> history = loadCandidateHistory(jobId, slot);
      Map<String, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("attempt", attempts); snapshot.put("qualityScore", qualityScore);
      snapshot.put("fallbackEligible", fallbackEligible); snapshot.put("question", question);
      snapshot.put("failureReason", errorMessage); snapshot.put("createdAt", Instant.now().toString());
      snapshot.put("generationScope", generationScope); snapshot.put("nextGenerationScope", nextGenerationScope);
      if (history.stream().noneMatch(value -> asNumber(value.get("attempt"), -1) == attempts)) history.add(snapshot);
      jdbc.update("update generation_job_items set status=?,attempts=?,question_json=?,error_code=?,error_message=?,candidate_history_json=?,best_score=case when coalesce(best_score,0)>? then best_score else ? end,fallback_selected=?,updated_at=? where job_id=? and sequence_no=?",
          status, attempts, json.writeValueAsString(question), errorCode, errorMessage == null ? null : limit(errorMessage),
          json.writeValueAsString(history), qualityScore, qualityScore, fallbackSelected,
          java.sql.Timestamp.from(Instant.now()), jobId, slot);
    } catch (Exception error) {
      throw new IllegalStateException("保存题位生成结果失败", error);
    }
  }

  private List<Map<String, Object>> loadCandidateHistory(UUID jobId, int slot) {
    List<String> rows = jdbc.query("select candidate_history_json from generation_job_items where job_id=? and sequence_no=?",
        (rs, n) -> rs.getString(1), jobId, slot);
    if (rows.isEmpty() || rows.getFirst() == null || rows.getFirst().isBlank()) return new ArrayList<>();
    try { return new ArrayList<>(json.readValue(rows.getFirst(), new TypeReference<>() { })); }
    catch (Exception error) { throw new IllegalStateException("题位候选历史数据损坏", error); }
  }

  private Map<String, Object> loadBestFallbackCandidate(UUID jobId, int slot,
      java.util.Collection<Map<String, Object>> accepted) {
    return loadCandidateHistory(jobId, slot).stream()
        .filter(value -> Boolean.parseBoolean(String.valueOf(value.get("fallbackEligible"))))
        .sorted(java.util.Comparator.comparingInt((Map<String, Object> value) -> asNumber(value.get("qualityScore"), 0)).reversed())
        .map(value -> {
          Map<String, Object> question = new LinkedHashMap<>(castMap(value.get("question")));
          question.put("qualityScore", asNumber(value.get("qualityScore"), 0));
          return question;
        })
        .findFirst().map(LinkedHashMap::new).orElse(null);
  }

  private boolean terminalModelFailure(String reason) {
    String text = Objects.toString(reason, "").toLowerCase(java.util.Locale.ROOT);
    return text.contains("额度") || text.contains("api key") || text.contains("unauthorized")
        || text.contains("authentication");
  }

  private Map<Integer, Map<String, Object>> loadAcceptedSlots(UUID jobId) {
    Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
    jdbc.query("select sequence_no,question_json from generation_job_items where job_id=? and status='ACCEPTED' order by sequence_no",
        rs -> {
          try { result.put(rs.getInt(1), json.readValue(rs.getString(2), new TypeReference<>() { })); }
          catch (Exception error) { throw new IllegalStateException("已生成题位数据损坏", error); }
        }, jobId);
    return result;
  }

  private Map<Integer, Integer> loadSlotAttempts(UUID jobId) {
    Map<Integer, Integer> result = new LinkedHashMap<>();
    jdbc.query("select sequence_no,attempts from generation_job_items where job_id=?", rs -> { result.put(rs.getInt(1), rs.getInt(2)); }, jobId);
    return result;
  }

  private Map<Integer, String> loadSlotFailures(UUID jobId) {
    Map<Integer, String> result = new LinkedHashMap<>();
    jdbc.query("select sequence_no,error_message from generation_job_items where job_id=? and error_message is not null",
        rs -> { result.put(rs.getInt(1), rs.getString(2)); }, jobId);
    return result;
  }

  private Map<Integer, RetryState> loadRetryStates(UUID jobId) {
    Map<Integer, RetryState> result = new LinkedHashMap<>();
    jdbc.query("select sequence_no,candidate_history_json from generation_job_items where job_id=?", rs -> {
      int slot = rs.getInt(1); RetryState state = RetryState.EMPTY;
      String raw = rs.getString(2);
      if (raw != null && !raw.isBlank()) {
        try {
          List<Map<String, Object>> history = json.readValue(raw, new TypeReference<>() { });
          for (Map<String, Object> snapshot : history) {
            state = state.after(Objects.toString(snapshot.get("generationScope"), "FULL"),
                asNumber(snapshot.get("attempt"), 1) > 1);
          }
        } catch (Exception error) { throw new IllegalStateException("题位重试历史数据损坏", error); }
      }
      result.put(slot, state);
    }, jobId);
    return result;
  }

  private Map<Integer, String> loadNextGenerationScopes(UUID jobId) {
    Map<Integer, String> result = new LinkedHashMap<>();
    jdbc.query("select sequence_no,candidate_history_json from generation_job_items where job_id=?", rs -> {
      int slot = rs.getInt(1); String raw = rs.getString(2); if (raw == null || raw.isBlank()) return;
      try {
        List<Map<String, Object>> history = json.readValue(raw, new TypeReference<>() { });
        history.stream().max(java.util.Comparator.comparingInt(value -> asNumber(value.get("attempt"), 0))).ifPresent(snapshot -> {
          String scope = Objects.toString(snapshot.get("nextGenerationScope"), "");
          if (Set.of("FULL", "OPTION").contains(scope)) result.put(slot, scope);
        });
      } catch (Exception error) { throw new IllegalStateException("题位重试历史数据损坏", error); }
    }, jobId);
    return result;
  }

  private int sequence(Map<String, Object> value) { return asNumber(value.get("sequence"), -1); }

  private record RetryState(int fullAttempts, int optionAttempts) {
    private static final RetryState EMPTY = new RetryState(0, 0);
    private RetryState after(String scope, boolean repairAttempt) {
      if (!repairAttempt) return this;
      return "OPTION".equals(scope) ? new RetryState(fullAttempts, optionAttempts + 1)
          : new RetryState(fullAttempts + 1, optionAttempts);
    }
  }

  public List<Map<String, Object>> generationItems(UUID jobId) {
    getJob(jobId);
    return jdbc.query("select sequence_no,question_type,difficulty,assessment_point,status,attempts,error_code,error_message,updated_at from generation_job_items where job_id=? order by sequence_no",
        (rs, row) -> {
          Map<String, Object> value = new LinkedHashMap<>();
          value.put("sequence", rs.getInt("sequence_no")); value.put("type", rs.getString("question_type"));
          value.put("difficulty", rs.getString("difficulty")); value.put("assessmentPoint", rs.getString("assessment_point"));
          value.put("status", rs.getString("status")); value.put("attempts", rs.getInt("attempts"));
          value.put("errorCode", rs.getString("error_code")); value.put("errorMessage", rs.getString("error_message"));
          value.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
          return value;
        }, jobId);
  }

  private void validateStandard(String profession, List<String> levels, List<String> points) {
    if (profession == null || profession.isBlank() || "未识别职业".equals(profession)) {
      throw new IllegalArgumentException("职业名称未识别，不能确认标准");
    }
    if (levels == null || levels.isEmpty() || levels.stream().allMatch(value -> value.contains("待人工"))) {
      throw new IllegalArgumentException("至少需要确认一个真实职业等级");
    }
    if (points == null || points.size() < 5) throw new IllegalArgumentException("至少需要 5 个完整、可命题的考核点");
    if (points.stream().anyMatch(value -> value.length() < 8)) throw new IllegalArgumentException("考核点存在过短残句，请先编辑修正");
  }

  private Job save(String type, Map<String, Object> request, List<Map<String, Object>> result) {
    var job = new Job(UUID.randomUUID(), type, "SUCCEEDED", 100, request, result, 0, null, Instant.now(), Instant.now());
    jobs.put(job.id(), job);
    persistJob(job);
    generationProgress.update(job.id(), "SUCCEEDED", 100, result.size(), result.size(), "任务已完成", null);
    return job;
  }

  private void persistStandard(Standard value) {
    try {
      int updated = jdbc.update("update occupational_standards set document_id=?,profession=?,occupation_code=?,status=?,schema_json=?,version=?,created_at=? where id=?",
          value.documentId(), value.profession(), value.occupationCode(), value.status(), json.writeValueAsString(value),
          value.version(), java.sql.Timestamp.from(value.updatedAt()), value.id());
      if (updated == 0) jdbc.update("insert into occupational_standards(id,document_id,profession,occupation_code,status,schema_json,version,created_at) values(?,?,?,?,?,?,?,?)",
          value.id(), value.documentId(), value.profession(), value.occupationCode(), value.status(),
          json.writeValueAsString(value), value.version(), java.sql.Timestamp.from(value.updatedAt()));
    } catch (Exception e) { throw new IllegalStateException("保存职业标准失败", e); }
  }

  private void persistVersion(Standard value) {
    try {
      jdbc.update("delete from occupational_standard_versions where standard_id=? and version=?", value.id(), value.version());
      jdbc.update("insert into occupational_standard_versions(id,standard_id,version,status,schema_json,created_at) values(?,?,?,?,?,?)",
          UUID.randomUUID(), value.id(), value.version(), value.status(), json.writeValueAsString(value),
          java.sql.Timestamp.from(value.updatedAt()));
    } catch (Exception e) { throw new IllegalStateException("保存职业标准版本失败", e); }
  }

  private void persistJob(Job value) {
    try {
      jdbc.update("insert into generation_jobs(id,owner_id,job_type,status,progress,request_json,result_json,retry_count,error_message,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
          value.id(), access.currentUserId(), value.type(), value.status(), value.progress(),
          json.writeValueAsString(value.request()), json.writeValueAsString(value.result()), value.retryCount(),
          value.errorMessage(), java.sql.Timestamp.from(value.createdAt()), java.sql.Timestamp.from(value.updatedAt()));
    } catch (Exception e) { throw new IllegalStateException("保存任务失败", e); }
  }

  private void storeUpdate(Job value) {
    jobs.put(value.id(), value);
    try {
      jdbc.update("update generation_jobs set status=?,progress=?,result_json=?,retry_count=?,error_message=?,updated_at=? where id=?",
          value.status(), value.progress(), json.writeValueAsString(value.result()), value.retryCount(),
          value.errorMessage(), java.sql.Timestamp.from(value.updatedAt()), value.id());
    } catch (Exception e) { throw new IllegalStateException("更新任务失败", e); }
  }

  private Job withState(Job job, String status, int progress, List<Map<String, Object>> result, int retries, String error) {
    return new Job(job.id(), job.type(), status, progress, job.request(), result, retries, error, job.createdAt(), Instant.now());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> castMap(Object value) { return (Map<String, Object>) value; }
  private String text(Map<String, Object> value, String key) { return Objects.toString(value.get(key), "").trim(); }
  private int asNumber(Object value, int fallback) {
    try { return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value)); }
    catch (Exception e) { return fallback; }
  }
  private String limit(String value) {
    String message = value == null ? "未提供错误信息" : value;
    return message.substring(0, Math.min(message.length(), 1800));
  }
  private int expectedItems(Job job) {
    Integer direct = null;
    try { direct = asNumber(job.request().get("expectedTotal"), -1); } catch (Exception ignored) { }
    if (direct != null && direct >= 0) return direct;
    try {
      Object blueprint = job.request().get("blueprintJobId");
      return blueprint == null ? job.result().size() : getJob(UUID.fromString(String.valueOf(blueprint))).result().size();
    } catch (Exception ignored) { return job.result().size(); }
  }
  private String classifyGenerationError(Exception error) {
    String message = Objects.toString(error.getMessage(), "");
    if (message.contains("额度")) return "MODEL_QUOTA_EXCEEDED";
    if (message.contains("模型") || message.contains("DeepSeek") || message.contains("JSON")) return "MODEL_UNAVAILABLE";
    if (message.contains("质量门禁") || message.contains("重复") || message.contains("证据")) return "QUALITY_REJECTED";
    return "FAILED";
  }
  private void requireConfirmed(Standard standard) {
    if (!"CONFIRMED".equals(standard.status())) throw new IllegalArgumentException("职业标准须经人工确认后才能生成，当前状态：" + standard.status());
  }
  private void validateTotal(int total) {
    if (total < 1 || total > 1000) throw new IllegalArgumentException("题目数量应在 1 到 1000 之间");
  }
  private String normalizeRefillPolicy(String value) {
    String policy = Objects.toString(value, "SINGLE_REFILL").trim().toUpperCase(java.util.Locale.ROOT);
    return "SINGLE_REFILL".equals(policy) ? policy : "SINGLE_REFILL";
  }
  private String normalizeGenerationMode(String value) {
    String mode = Objects.toString(value, "FAST").trim().toUpperCase(java.util.Locale.ROOT);
    if (Set.of("EXPERT", "PROFESSIONAL_PRO").contains(mode)) return "EXPERT";
    if (Set.of("FAST", "BALANCED", "TIERED").contains(mode)) return "FAST";
    throw new IllegalArgumentException("命题模式只能为 FAST（快速考核）或 EXPERT（专家命题）");
  }
  private List<String> normalizeTypes(List<String> types) {
    if (types == null || types.isEmpty()) return TYPES;
    var valid = types.stream().filter(TYPES::contains).distinct().toList();
    if (valid.isEmpty()) throw new IllegalArgumentException("未提供有效题型");
    return valid;
  }
  private List<String> allocateTypes(List<String> types, Map<String, Integer> typeCounts, int total) {
    if (typeCounts == null || typeCounts.isEmpty()) {
      List<String> result = new ArrayList<>(); for (int i = 0; i < total; i++) result.add(types.get(i % types.size())); return result;
    }
    List<String> result = new ArrayList<>(); int sum = 0;
    for (String type : TYPES) {
      Integer count = typeCounts.get(type); if (count == null) continue;
      if (count < 0 || count > 1000) throw new IllegalArgumentException("题型数量须为 0 到 1000");
      sum += count; for (int i = 0; i < count; i++) result.add(type);
    }
    if (sum != total) throw new IllegalArgumentException("各题型计划数量之和必须等于总题数 " + total + "，当前为 " + sum);
    if (result.isEmpty()) throw new IllegalArgumentException("至少应配置一种题型数量");
    return result;
  }
  public Map<String, Object> levelPreset(String level) {
    String group = levelGroup(level);
    return switch (group) {
      case "FOUNDATION" -> Map.of("level", level, "group", group, "label", "基础入门", "easyPercent", 60, "mediumPercent", 35, "hardPercent", 5,
          "description", "以基础概念、规范识别和标准操作为主，少量综合判断。" );
      case "ADVANCED" -> Map.of("level", level, "group", group, "label", "高级综合", "easyPercent", 15, "mediumPercent", 50, "hardPercent", 35,
          "description", "以复杂情境、综合分析、方案判断和异常处置为主。" );
      default -> Map.of("level", level, "group", group, "label", "熟练应用", "easyPercent", 35, "mediumPercent", 50, "hardPercent", 15,
          "description", "兼顾规范应用、工作判断和常见情境分析。" );
    };
  }
  private String difficultyForLevel(String level, int index, int total) {
    Map<String, Object> preset = levelPreset(level);
    int easy = (int) preset.get("easyPercent");
    int medium = (int) preset.get("mediumPercent");
    int easyCount = (int) Math.round(total * easy / 100d);
    int mediumCount = (int) Math.round(total * medium / 100d);
    if (easyCount + mediumCount > total) mediumCount = Math.max(0, total - easyCount);
    return index < easyCount ? "EASY" : index < easyCount + mediumCount ? "MEDIUM" : "HARD";
  }
  private String cognitiveTarget(String difficulty) {
    return switch (difficulty) {
      case "EASY" -> "RECOGNIZE";
      case "MEDIUM" -> "APPLY";
      default -> "ANALYZE_DECIDE";
    };
  }
  private String abilityObjective(String level, String point, String difficulty) {
    String action = switch (difficulty) {
      case "EASY" -> "识别岗位规范并避免常见错误";
      case "MEDIUM" -> "在给定工作条件下选择并实施适当做法";
      default -> "在多项约束或异常情境下作出可解释的处置决策";
    };
    return "职业层级：" + level + "；围绕“" + shortenPoint(point) + "”考查：" + action;
  }
  private String difficultyRationale(String level, String difficulty) {
    return switch (difficulty) {
      case "EASY" -> level + "的基础规范识别与关键错误规避";
      case "MEDIUM" -> level + "在具体任务条件下的工艺/规范应用";
      default -> level + "在质量、安全、成本或异常约束下的综合判断与方案选择";
    };
  }
  private String shortenPoint(String point) {
    String clean = Objects.toString(point, "").replaceAll("职业功能：|工作内容：|技能要求：|相关知识：", "")
        .replace('|', '；').trim();
    return clean.substring(0, Math.min(clean.length(), 80));
  }
  private String levelGroup(String level) {
    String normalized = Objects.toString(level, "").replaceAll("\\s", "");
    if (normalized.contains("一级") || normalized.contains("二级") || normalized.contains("技师") || normalized.contains("高级技师")) return "ADVANCED";
    if (normalized.contains("四级") || normalized.contains("五级") || normalized.contains("初级") || normalized.contains("初级工")) return "FOUNDATION";
    return "PROFICIENT";
  }

  private List<String> compatiblePoints(List<String> points, String type, int offset) {
    if (points == null || points.isEmpty()) return List.of("待人工补充考点");
    int required = assessmentPointCount(type); List<String> selected = new ArrayList<>();
    for (int i = 0; i < required; i++) selected.add(points.get(Math.floorMod(offset + i, points.size())));
    return selected.stream().distinct().toList();
  }

  /** A level blueprint must never silently draw examination points from another occupational level. */
  private List<String> assessmentPointsForLevel(Standard standard, String level) {
    List<LevelDetail> details = standardLevelDetails(standard);
    for (LevelDetail detail : details) {
      if (!Objects.equals(detail.level(), level)) continue;
      List<String> points = flattenLevelOutline(detail.outline());
      if (!points.isEmpty()) return points;
    }
    return standard.assessmentPoints();
  }

  private List<String> flattenLevelOutline(List<OccupationalStandardParserService.StandardOutlineNode> outline) {
    if (outline == null) return List.of();
    List<String> result = new ArrayList<>();
    for (var node : outline) {
      if (node.assessmentPoints() != null) for (var point : node.assessmentPoints()) {
        result.add("职业功能：" + Objects.requireNonNullElse(node.name(), "") + "｜工作内容："
            + Objects.requireNonNullElse(point.name(), "") + "｜技能要求："
            + Objects.requireNonNullElse(point.skillRequirement(), "") + "｜相关知识："
            + Objects.requireNonNullElse(point.relatedKnowledgeRequirement(), ""));
      }
      result.addAll(flattenLevelOutline(node.children()));
    }
    return result.stream().filter(value -> value.length() >= 18).distinct().toList();
  }

  private List<LevelDetail> standardLevelDetails(Standard standard) {
    return standard.levelDetails() == null ? List.of() : standard.levelDetails();
  }

  private List<String> normalizeAssessmentPoints(Map<String, Object> item, String type) {
    List<String> values = new ArrayList<>(); Object raw = item.get("assessmentPoints");
    if (raw instanceof List<?> list) list.forEach(value -> { String v = Objects.toString(value, "").trim(); if (!v.isBlank()) values.add(v); });
    if (values.isEmpty()) for (String value : text(item, "assessmentPoint").split("[；;\\n]")) if (!value.trim().isBlank()) values.add(value.trim());
    int required = assessmentPointCount(type);
    if (values.size() != required) throw new IllegalArgumentException(type + " 必须关联 " + required + " 个考点");
    if (values.stream().anyMatch(value -> value.length() > 300)) throw new IllegalArgumentException("考点不能超过 300 字");
    return values.stream().distinct().toList();
  }

  private int assessmentPointCount(String type) {
    return switch (type) {
      case "SHORT_ANSWER", "CALCULATION" -> 2;
      case "ESSAY", "CASE_ANALYSIS", "COMPREHENSIVE" -> 3;
      default -> 1;
    };
  }

  private String retrievalQuery(String point, String type) {
    return switch (type) {
      case "CALCULATION" -> point + " 计算 数据 参数 公式 结果";
      case "ESSAY", "CASE_ANALYSIS", "COMPREHENSIVE" -> point + " 分析评价 判断处理 依据";
      default -> point;
    };
  }

  private record StoredJob(String type, String status, int progress, String request, String result,
      int retryCount, String errorMessage, Instant createdAt, Instant updatedAt) { }
  public record StandardVersion(int version, String status, String schemaJson, Instant createdAt) { }
  public record Standard(UUID id, UUID documentId, UUID parentStandardId, String standardKind,
      String profession, String occupationCode,
      List<String> levels, List<String> assessmentPoints, List<LevelDetail> levelDetails,
      boolean levelDetailsDerived, int version, String status, Instant updatedAt) { }
  public record StandardExtractionInfo(String method, List<String> diagnostics) { }
  public record Job(UUID id, String type, String status, int progress, Map<String, Object> request,
      List<Map<String, Object>> result, int retryCount, String errorMessage, Instant createdAt, Instant updatedAt) { }
  public record QueueResult(Job job, boolean created) { }
  public record QuestionGenerationDesign(int sequence, int attempt, String stage, String model, String reasoningEffort,
      Map<String, Object> design, Map<String, Object> validation, long durationMs, Instant createdAt) { }
  @FunctionalInterface public interface ExportProgress { void onProgress(int processed, int total); }
}
