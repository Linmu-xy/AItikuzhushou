package com.tikuzhushou.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A live archive of questions that currently have a passing human review. */
@Service
public class QuestionBankService {
  private static final ZoneId ARCHIVE_ZONE = ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseAccessService access;

  public QuestionBankService(JdbcTemplate jdbc, ObjectMapper json, KnowledgeBaseAccessService access) {
    this.jdbc = jdbc;
    this.json = json;
    this.access = access;
  }

  public Page search(UUID knowledgeBaseId, boolean unlinked, LocalDate from, LocalDate to, String query, int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("分页参数无效");
    if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
    if (knowledgeBaseId != null) access.assertKnowledgeBase(knowledgeBaseId);
    UUID owner = access.currentUserId();
    String needle = Objects.toString(query, "").trim().toLowerCase(Locale.ROOT);
    if (needle.length() > 200) throw new IllegalArgumentException("搜索关键词不能超过 200 字");

    List<Item> items = new ArrayList<>();
    items.addAll(jdbc.query("select i.id,i.run_id,i.sequence_no,i.question_type,i.difficulty,i.question_json,i.reviewed_at,i.updated_at,p.id project_id,p.name project_name,p.knowledge_base_id,k.name knowledge_base_name "
        + "from exam_project_generation_items i join exam_project_generation_runs r on r.id=i.run_id "
        + "join exam_projects p on p.id=r.project_id join knowledge_bases k on k.id=p.knowledge_base_id "
        + "where p.owner_id=? and i.status='APPROVED'", this::projectItem, owner));

    Map<UUID, LegacySource> sources = new HashMap<>();
    Map<UUID, String> baseNames = new HashMap<>();
    jdbc.query("select id,name from knowledge_bases where owner_id=?", rs -> {
      baseNames.put(rs.getObject("id", UUID.class), rs.getString("name"));
    }, owner);
    items.addAll(jdbc.query("select r.id,r.job_id,r.sequence_no,r.question_json,r.approved_at,r.updated_at,j.request_json "
        + "from question_reviews r join generation_jobs j on j.id=r.job_id "
        + "where r.owner_id=? and j.owner_id=? and r.review_status in ('APPROVED','LOCKED')",
        (rs, row) -> legacyItem(rs, sources, baseNames), owner, owner));

    List<Item> matching = items.stream()
        .filter(item -> unlinked ? item.knowledgeBaseId() == null : knowledgeBaseId == null || knowledgeBaseId.equals(item.knowledgeBaseId()))
        .filter(item -> from == null || !item.archiveDate().isBefore(from))
        .filter(item -> to == null || !item.archiveDate().isAfter(to))
        .filter(item -> needle.isBlank() || searchable(item).contains(needle))
        .sorted(Comparator.comparing(Item::reviewedAt).reversed().thenComparing(Item::id))
        .toList();
    long offset = (long) page * size;
    List<Item> slice = offset >= matching.size() ? List.of() : matching.subList((int) offset, (int) Math.min(offset + size, matching.size()));
    return new Page(List.copyOf(slice), matching.size(), page, size, offset + slice.size() < matching.size());
  }

  private Item projectItem(ResultSet rs, int row) throws SQLException {
    Map<String, Object> question = readMap(rs.getString("question_json"));
    Instant reviewedAt = timestamp(rs, "reviewed_at", "updated_at");
    return new Item(rs.getObject("id", UUID.class), "PROJECT", rs.getObject("project_id", UUID.class),
        rs.getObject("run_id", UUID.class), rs.getInt("sequence_no"), rs.getObject("knowledge_base_id", UUID.class),
        rs.getString("knowledge_base_name"), rs.getString("project_name"), rs.getString("question_type"),
        rs.getString("difficulty"), question, reviewedAt, reviewedAt.atZone(ARCHIVE_ZONE).toLocalDate());
  }

  private Item legacyItem(ResultSet rs, Map<UUID, LegacySource> sources, Map<UUID, String> baseNames) throws SQLException {
    UUID jobId = rs.getObject("job_id", UUID.class);
    LegacySource source = sources.computeIfAbsent(jobId, ignored -> legacySource(rsString(rs, "request_json")));
    UUID baseId = source.knowledgeBaseId();
    Instant reviewedAt = timestamp(rs, "approved_at", "updated_at");
    Map<String, Object> question = readMap(rs.getString("question_json"));
    return new Item(rs.getObject("id", UUID.class), "LEGACY_JOB", jobId, null, rs.getInt("sequence_no"),
        baseId, baseNames.getOrDefault(baseId, "未关联知识库"), source.name(), text(question.get("type")),
        text(question.get("difficulty")), question, reviewedAt, reviewedAt.atZone(ARCHIVE_ZONE).toLocalDate());
  }

  private LegacySource legacySource(String requestJson) {
    Map<String, Object> request = readMap(requestJson);
    UUID baseId = uuid(request.get("knowledgeBaseId"));
    UUID blueprintId = uuid(request.get("blueprintJobId"));
    if (baseId == null && blueprintId != null) {
      List<String> blueprints = jdbc.query("select request_json from generation_jobs where id=?",
          (rs, row) -> rs.getString(1), blueprintId);
      if (!blueprints.isEmpty()) baseId = uuid(readMap(blueprints.getFirst()).get("knowledgeBaseId"));
    }
    String name = "历史题库任务";
    if ("EXCEL_IMPORT".equals(text(request.get("source")))) name = "Excel 导入任务";
    return new LegacySource(baseId, name);
  }

  private String searchable(Item item) {
    StringBuilder value = new StringBuilder(item.knowledgeBaseName()).append(' ').append(item.sourceName());
    for (String key : List.of("stem", "options", "answer", "analysis", "assessmentPoint", "sourceRef", "sourceExcerpt")) {
      value.append(' ').append(text(item.question().get(key)));
    }
    return value.toString().toLowerCase(Locale.ROOT);
  }

  private Map<String, Object> readMap(String raw) {
    try {
      Map<String, Object> value = json.readValue(Objects.toString(raw, "{}"), new TypeReference<>() { });
      return value == null ? Map.of() : value;
    }
    catch (Exception error) { return Map.of(); }
  }
  private UUID uuid(Object value) {
    try { return value == null ? null : UUID.fromString(String.valueOf(value)); }
    catch (IllegalArgumentException ignored) { return null; }
  }
  private String text(Object value) { return Objects.toString(value, ""); }
  private Instant timestamp(ResultSet rs, String primary, String fallback) throws SQLException {
    var value = rs.getTimestamp(primary);
    return (value == null ? rs.getTimestamp(fallback) : value).toInstant();
  }
  private String rsString(ResultSet rs, String column) {
    try { return rs.getString(column); }
    catch (SQLException error) { throw new IllegalStateException("读取历史任务失败", error); }
  }

  private record LegacySource(UUID knowledgeBaseId, String name) { }
  public record Item(UUID id, String sourceType, UUID sourceId, UUID runId, int sequence,
      UUID knowledgeBaseId, String knowledgeBaseName, String sourceName, String questionType,
      String difficulty, Map<String, Object> question, Instant reviewedAt, LocalDate archiveDate) { }
  public record Page(List<Item> items, int total, int page, int size, boolean hasMore) { }
}
