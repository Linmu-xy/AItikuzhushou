package com.tikuzhushou.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds a durable, reviewable question-slot plan from one frozen evidence snapshot. */
@Service
public class ExamProjectGenerationPlanService {
  private static final List<String> DIFFICULTIES = List.of("EASY", "MEDIUM", "HARD");

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final ExamProjectService projects;
  private final ExamProjectEvidenceService evidence;
  private final KnowledgeBaseAccessService access;
  private final KnowledgeAssessmentPlanningService assessmentPlanning;

  public ExamProjectGenerationPlanService(JdbcTemplate jdbc, ObjectMapper json,
      ExamProjectService projects, ExamProjectEvidenceService evidence, KnowledgeBaseAccessService access,
      KnowledgeAssessmentPlanningService assessmentPlanning) {
    this.jdbc = jdbc;
    this.json = json;
    this.projects = projects;
    this.evidence = evidence;
    this.access = access;
    this.assessmentPlanning = assessmentPlanning;
  }

  /** Creates one plan per evidence snapshot; repeated clicks return the existing plan. */
  @Transactional
  public TaskView create(UUID projectId) {
    ExamProjectService.ProjectView project = projects.get(projectId);
    ExamProjectEvidenceService.PreparationView snapshot = evidence.latest(projectId);
    if (snapshot == null || !"READY".equals(snapshot.status())) {
      throw new IllegalArgumentException("请先完成命题依据检查并获得 READY 快照");
    }
    TaskView existing = findBySnapshot(snapshot.snapshotId());
    boolean reusable = existing != null && (!"KNOWLEDGE_BASE".equals(project.mode())
        || existing.assessmentPlan() != null && KnowledgeAssessmentPlanningService.OPEN_ASSESSMENT_VERSION
            .equals(existing.assessmentPlan().pipelineVersion()));
    if (reusable && QuestionTypeOrder.VERSION.equals(existing.orderingVersion())) return existing;

    List<ScoreSpec> scoring = scoring(project.scoringStructureJson());
    DifficultyProfile difficulty = difficulty(project.difficultyProfileJson());
    int questionsPerVariant = scoring.stream().mapToInt(ScoreSpec::count).sum();
    int totalQuestions = questionsPerVariant * project.variantCount();
    List<String> sourceRoles = sourceRoles(snapshot.sources());
    KnowledgeAssessmentPlanningService.AssessmentPlan assessmentPlan = null;
    if ("KNOWLEDGE_BASE".equals(project.mode())) {
      boolean automatic = scoring.size() == 1 && "CUSTOM".equals(scoring.getFirst().questionType())
          && scoring.getFirst().typeLabel().contains("智能");
      List<Map<String, Object>> fixedSlots;
      if (automatic) {
        fixedSlots = new ArrayList<>();
        int[] distribution = distribute(questionsPerVariant, difficulty);
        for (int index = 0; index < DIFFICULTIES.size(); index++)
          for (int slot = 0; slot < distribution[index]; slot++)
            fixedSlots.add(Map.of("difficulty", DIFFICULTIES.get(index)));
      } else fixedSlots = fixedSlots(scoring, difficulty);
      assessmentPlan = reusable ? existing.assessmentPlan()
          : assessmentPlanning.propose(project, snapshot, questionsPerVariant, fixedSlots);
      assessmentPlan = groupAssessmentPlan(assessmentPlan);
    }
    Instant now = Instant.now();
    UUID taskId = UUID.randomUUID();
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("projectId", project.id());
    request.put("evidenceSnapshotId", snapshot.snapshotId());
    request.put("snapshotHash", snapshot.snapshotHash());
    request.put("requirementText", Objects.toString(project.requirementText(), ""));
    request.put("variantCount", project.variantCount());
    request.put("difficultyProfile", Map.of("easy", difficulty.easy(), "medium", difficulty.medium(), "hard", difficulty.hard()));
    request.put("scoringStructure", scoring.stream().map(ScoreSpec::asMap).toList());
    request.put("sourceRoles", sourceRoles);
    request.put("orderingVersion", QuestionTypeOrder.VERSION);
    if (assessmentPlan != null) request.put("assessmentPlan", assessmentPlan);
    String requestJson = write(request);

    jdbc.update("update exam_project_generation_tasks set status='SUPERSEDED',updated_at=? where project_id=? and status='READY'",
        Timestamp.from(now), projectId);
    jdbc.update("insert into exam_project_generation_tasks(id,project_id,evidence_snapshot_id,status,variant_count,question_count_per_variant,total_question_count,request_json,created_by,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
        taskId, projectId, snapshot.snapshotId(), "READY", project.variantCount(), questionsPerVariant, totalQuestions,
        requestJson, currentUserId(), Timestamp.from(now), Timestamp.from(now));

    int itemCount = 0;
    for (int variant = 1; variant <= project.variantCount(); variant++) {
      int sequence = 1;
      String label = variantLabel(variant);
      if (assessmentPlan != null) {
        for (KnowledgeAssessmentPlanningService.PlanItem item : assessmentPlan.items()) {
          jdbc.update("insert into exam_project_variant_items(id,generation_task_id,variant_no,variant_label,sequence_no,question_type,type_label,difficulty,points,source_roles_json,status,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
              UUID.randomUUID(), taskId, variant, label, item.sequence(), item.type(), typeLabel(item.type()),
              item.difficulty(), item.points(), write(sourceRoles), "PLANNED", Timestamp.from(now));
          itemCount++;
        }
        continue;
      }
      for (ScoreSpec spec : scoring) {
        int[] distribution = distribute(spec.count(), difficulty);
        for (int difficultyIndex = 0; difficultyIndex < DIFFICULTIES.size(); difficultyIndex++) {
          for (int item = 0; item < distribution[difficultyIndex]; item++) {
            jdbc.update("insert into exam_project_variant_items(id,generation_task_id,variant_no,variant_label,sequence_no,question_type,type_label,difficulty,points,source_roles_json,status,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), taskId, variant, label, sequence++, spec.questionType(), spec.typeLabel(),
                DIFFICULTIES.get(difficultyIndex), spec.points(), write(sourceRoles), "PLANNED", Timestamp.from(now));
            itemCount++;
          }
        }
      }
    }
    if (itemCount != totalQuestions) throw new IllegalStateException("生成题位计划数量不一致");
    return get(taskId);
  }

  public List<TaskView> list(UUID projectId) {
    projects.get(projectId);
    return jdbc.query("select id,project_id,evidence_snapshot_id,status,variant_count,question_count_per_variant,total_question_count,request_json,created_at,updated_at from exam_project_generation_tasks where project_id=? order by created_at desc",
        (rs, row) -> taskSummary(rs), projectId).stream().map(this::withItems).toList();
  }

  public TaskView get(UUID taskId) {
    List<TaskView> rows = jdbc.query("select id,project_id,evidence_snapshot_id,status,variant_count,question_count_per_variant,total_question_count,request_json,created_at,updated_at from exam_project_generation_tasks where id=?",
        (rs, row) -> taskSummary(rs), taskId);
    if (rows.isEmpty()) throw new IllegalArgumentException("变式题位计划不存在");
    TaskView task = rows.getFirst();
    projects.get(task.projectId());
    return withItems(task);
  }

  private TaskView findBySnapshot(UUID snapshotId) {
    return jdbc.query("select id,project_id,evidence_snapshot_id,status,variant_count,question_count_per_variant,total_question_count,request_json,created_at,updated_at from exam_project_generation_tasks where evidence_snapshot_id=? and status='READY' order by created_at desc limit 1",
        (rs, row) -> taskSummary(rs), snapshotId).stream().findFirst().map(this::withItems).orElse(null);
  }

  private TaskView withItems(TaskView task) {
    List<VariantItemView> items = jdbc.query("select variant_no,variant_label,sequence_no,question_type,type_label,difficulty,points,status from exam_project_variant_items where generation_task_id=? order by variant_no,sequence_no",
        (rs, row) -> new VariantItemView(rs.getInt("variant_no"), rs.getString("variant_label"), rs.getInt("sequence_no"),
            rs.getString("question_type"), rs.getString("type_label"), rs.getString("difficulty"), rs.getInt("points"), rs.getString("status")), task.id());
    return task.withItems(items);
  }

  private TaskView taskSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
    Map<String, Object> request = readMap(rs.getString("request_json"));
    KnowledgeAssessmentPlanningService.AssessmentPlan assessmentPlan = request.get("assessmentPlan") == null ? null
        : json.convertValue(request.get("assessmentPlan"), KnowledgeAssessmentPlanningService.AssessmentPlan.class);
    return new TaskView(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
        rs.getObject("evidence_snapshot_id", UUID.class), rs.getString("status"), rs.getInt("variant_count"),
        rs.getInt("question_count_per_variant"), rs.getInt("total_question_count"),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), assessmentPlan, List.of(),
        Objects.toString(request.get("orderingVersion"), "LEGACY"));
  }

  static KnowledgeAssessmentPlanningService.AssessmentPlan groupAssessmentPlan(KnowledgeAssessmentPlanningService.AssessmentPlan plan) {
    var sorted = plan.items().stream().sorted(Comparator
        .comparingInt((KnowledgeAssessmentPlanningService.PlanItem item) -> QuestionTypeOrder.rank(item.type(), item.type()))
        .thenComparing(item -> QuestionTypeOrder.key(item.type(), item.type()))
        .thenComparingInt(KnowledgeAssessmentPlanningService.PlanItem::sequence)).toList();
    List<KnowledgeAssessmentPlanningService.PlanItem> items = new ArrayList<>();
    for (var item : sorted) {
      items.add(new KnowledgeAssessmentPlanningService.PlanItem(items.size() + 1, item.competency(), item.task(),
          item.type(), item.difficulty(), item.points(), item.documentId(), item.page(), item.searchQuery(),
          item.needsImage(), item.visualSummary(), item.requiredMaterial(), item.knowledgeUse(), item.answerability(), item.webEvidence()));
    }
    return new KnowledgeAssessmentPlanningService.AssessmentPlan(plan.summary(), List.copyOf(items), plan.webEvidence(),
        plan.pipelineVersion(), plan.corpusContext(), plan.domainBrief());
  }

  private List<Map<String, Object>> fixedSlots(List<ScoreSpec> scoring, DifficultyProfile difficulty) {
    List<Map<String, Object>> slots = new ArrayList<>();
    for (ScoreSpec spec : scoring) {
      int[] distribution = distribute(spec.count(), difficulty);
      for (int index = 0; index < distribution.length; index++) {
        for (int i = 0; i < distribution[index]; i++) slots.add(Map.of("type", spec.questionType(),
            "difficulty", DIFFICULTIES.get(index), "points", spec.points()));
      }
    }
    return slots;
  }

  private String typeLabel(String type) {
    return QuestionTypeOrder.label(type, type);
  }

  private List<ScoreSpec> scoring(String raw) {
    List<Map<String, Object>> rows = readList(raw);
    if (rows.isEmpty()) throw new IllegalArgumentException("请先填写至少一种题型、题量和分值");
    List<ScoreSpec> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      String label = Objects.toString(row.get("type"), "").trim();
      int count = positiveInt(row.get("count"), "题量");
      int points = positiveInt(row.get("points"), "每题分值");
      if (label.isBlank()) throw new IllegalArgumentException("题型名称不能为空");
      result.add(new ScoreSpec(canonicalType(label), label, count, points));
    }
    return result.stream().sorted(Comparator.comparingInt((ScoreSpec spec) -> QuestionTypeOrder.rank(spec.questionType(), spec.typeLabel()))
        .thenComparing(spec -> QuestionTypeOrder.key(spec.questionType(), spec.typeLabel()))).toList();
  }

  private DifficultyProfile difficulty(String raw) {
    Map<String, Object> values = readMap(raw);
    int easy = boundedInt(values.get("easy"), "简单难度");
    int medium = boundedInt(values.get("medium"), "中等难度");
    int hard = boundedInt(values.get("hard"), "困难难度");
    if (easy + medium + hard != 100) throw new IllegalArgumentException("难度比例合计必须为 100%");
    return new DifficultyProfile(easy, medium, hard);
  }

  private int[] distribute(int count, DifficultyProfile difficulty) {
    int[] percentages = { difficulty.easy(), difficulty.medium(), difficulty.hard() };
    int[] result = new int[3];
    double[] fractions = new double[3];
    int assigned = 0;
    for (int index = 0; index < percentages.length; index++) {
      double exact = count * percentages[index] / 100.0;
      result[index] = (int) Math.floor(exact);
      fractions[index] = exact - result[index];
      assigned += result[index];
    }
    List<Integer> remainderOrder = List.of(0, 1, 2).stream()
        .sorted(Comparator.<Integer>comparingDouble(index -> fractions[index]).reversed())
        .toList();
    for (int index = 0; index < count - assigned; index++) result[remainderOrder.get(index % remainderOrder.size())]++;
    return result;
  }

  private List<String> sourceRoles(List<Map<String, Object>> sources) {
    LinkedHashSet<String> roles = new LinkedHashSet<>();
    for (Map<String, Object> source : sources) {
      String role = Objects.toString(source.get("sourceRole"), "OTHER").trim().toUpperCase(Locale.ROOT);
      if (!role.isBlank()) roles.add(role);
    }
    return List.copyOf(roles);
  }

  private String canonicalType(String label) {
    return QuestionTypeOrder.canonical(label);
  }

  private int positiveInt(Object value, String label) {
    int result = number(value, label);
    if (result <= 0) throw new IllegalArgumentException(label + "必须大于 0");
    return result;
  }

  private int boundedInt(Object value, String label) {
    int result = number(value, label);
    if (result < 0 || result > 100) throw new IllegalArgumentException(label + "必须为 0-100");
    return result;
  }

  private int number(Object value, String label) {
    if (value instanceof Number number) return number.intValue();
    try { return Integer.parseInt(Objects.toString(value, "").trim()); }
    catch (NumberFormatException error) { throw new IllegalArgumentException(label + "格式无效"); }
  }

  private List<Map<String, Object>> readList(String raw) {
    try { return json.readValue(Objects.toString(raw, "[]"), new TypeReference<>() { }); }
    catch (Exception error) { throw new IllegalArgumentException("分值结构格式无效"); }
  }

  private Map<String, Object> readMap(String raw) {
    try { return json.readValue(Objects.toString(raw, "{}"), new TypeReference<>() { }); }
    catch (Exception error) { throw new IllegalArgumentException("难度结构格式无效"); }
  }

  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (Exception error) { throw new IllegalStateException("保存变式题位计划失败", error); }
  }

  private UUID currentUserId() {
    return access.currentUserId();
  }

  private String variantLabel(int variant) {
    return variant <= 26 ? String.valueOf((char) ('A' + variant - 1)) : "V" + variant;
  }

  private record ScoreSpec(String questionType, String typeLabel, int count, int points) {
    Map<String, Object> asMap() { return Map.of("type", typeLabel, "questionType", questionType, "count", count, "points", points); }
  }
  private record DifficultyProfile(int easy, int medium, int hard) { }

  public record TaskView(UUID id, UUID projectId, UUID evidenceSnapshotId, String status, int variantCount,
      int questionCountPerVariant, int totalQuestionCount, Instant createdAt, Instant updatedAt,
      KnowledgeAssessmentPlanningService.AssessmentPlan assessmentPlan, List<VariantItemView> items, String orderingVersion) {
    public TaskView(UUID id, UUID projectId, UUID evidenceSnapshotId, String status, int variantCount,
        int questionCountPerVariant, int totalQuestionCount, Instant createdAt, Instant updatedAt,
        KnowledgeAssessmentPlanningService.AssessmentPlan assessmentPlan, List<VariantItemView> items) {
      this(id, projectId, evidenceSnapshotId, status, variantCount, questionCountPerVariant, totalQuestionCount,
          createdAt, updatedAt, assessmentPlan, items, QuestionTypeOrder.VERSION);
    }
    TaskView withItems(List<VariantItemView> value) {
      return new TaskView(id, projectId, evidenceSnapshotId, status, variantCount, questionCountPerVariant,
          totalQuestionCount, createdAt, updatedAt, assessmentPlan, value, orderingVersion);
    }
  }

  public record VariantItemView(int variantNo, String variantLabel, int sequenceNo, String questionType,
      String typeLabel, String difficulty, int points, String status) { }
}
