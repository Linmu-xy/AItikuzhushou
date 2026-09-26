package com.tikuzhushou.assistant;

import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Text-only, conversation-scoped files. These never enter source_documents or the knowledge-base index. */
@Service
public class AssistantAttachmentService {
  private static final int MAX_FILES = 3;
  private static final int MAX_TEXT = 40_000;
  private static final long MAX_BYTES = 8L * 1024 * 1024;
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseAccessService access;

  public AssistantAttachmentService(JdbcTemplate jdbc, KnowledgeBaseAccessService access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  public List<AttachmentView> list(UUID conversationId) {
    requireConversation(conversationId);
    return jdbc.query("select id,original_name,media_type,size_bytes,truncated,created_at from assistant_attachments where conversation_id=? and owner_id=? order by created_at,id",
        (rs, row) -> new AttachmentView(rs.getObject("id", UUID.class), rs.getString("original_name"),
            rs.getString("media_type"), rs.getLong("size_bytes"), rs.getBoolean("truncated"),
            rs.getTimestamp("created_at").toInstant()), conversationId, access.currentUserId());
  }

  public AttachmentView upload(UUID conversationId, MultipartFile file) throws IOException {
    requireConversation(conversationId);
    if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
      throw new IllegalArgumentException("会话文件不能为空且不得超过 8MB");
    }
    Integer count = jdbc.queryForObject("select count(*) from assistant_attachments where conversation_id=? and owner_id=?",
        Integer.class, conversationId, access.currentUserId());
    if (count != null && count >= MAX_FILES) throw new IllegalArgumentException("每个会话最多上传 3 份文件，请先移除旧文件");
    String name = Objects.toString(file.getOriginalFilename(), "资料").replaceAll("[\\/\\r\\n]", "_").trim();
    if (name.isBlank()) name = "资料";
    if (name.length() > 255) name = name.substring(0, 255);
    String lower = name.toLowerCase(Locale.ROOT);
    String mediaType = mediaType(lower);
    byte[] bytes = file.getBytes();
    String extracted;
    try { extracted = extract(bytes, mediaType); }
    catch (Exception error) { throw new IllegalArgumentException("文件无法提取文字；扫描件请上传到知识库进行 OCR", error); }
    extracted = extracted.replace('\u0000', ' ').trim();
    if (extracted.length() < 20) throw new IllegalArgumentException("文件没有足够的可读文字；扫描件请上传到知识库进行 OCR");
    boolean truncated = extracted.length() > MAX_TEXT;
    if (truncated) extracted = extracted.substring(0, MAX_TEXT);
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.update("insert into assistant_attachments(id,conversation_id,owner_id,original_name,media_type,size_bytes,extracted_text,truncated,created_at) values(?,?,?,?,?,?,?,?,?)",
        id, conversationId, access.currentUserId(), name, mediaType, bytes.length, extracted, truncated, Timestamp.from(now));
    jdbc.update("update assistant_conversations set updated_at=? where id=? and owner_id=?",
        Timestamp.from(now), conversationId, access.currentUserId());
    return new AttachmentView(id, name, mediaType, bytes.length, truncated, now);
  }

  public void delete(UUID conversationId, UUID attachmentId) {
    requireConversation(conversationId);
    int changed = jdbc.update("delete from assistant_attachments where id=? and conversation_id=? and owner_id=?",
        attachmentId, conversationId, access.currentUserId());
    if (changed == 0) throw new IllegalArgumentException("会话文件不存在");
  }

  /** Returns small, query-relevant passages with stable source labels for answer citations. */
  public List<Passage> relevant(UUID conversationId, String question) {
    requireConversation(conversationId);
    List<Stored> files = jdbc.query("select id,original_name,extracted_text from assistant_attachments where conversation_id=? and owner_id=? order by created_at,id",
        (rs, row) -> new Stored(rs.getObject("id", UUID.class), rs.getString("original_name"), rs.getString("extracted_text")),
        conversationId, access.currentUserId());
    Set<String> terms = terms(question);
    List<Passage> result = new ArrayList<>();
    for (Stored file : files) {
      List<Passage> candidates = new ArrayList<>();
      String content = Objects.toString(file.text(), "");
      for (int start = 0; start < content.length(); start += 800) {
        String excerpt = content.substring(start, Math.min(content.length(), start + 900)).replaceAll("\\s+", " ").trim();
        if (excerpt.isBlank()) continue;
        String lower = excerpt.toLowerCase(Locale.ROOT);
        int score = terms.stream().mapToInt(term -> lower.contains(term) ? 1 : 0).sum();
        candidates.add(new Passage(file.id(), file.name(), start, excerpt, score));
      }
      candidates.stream().sorted(Comparator.comparingInt(Passage::score).reversed().thenComparingInt(Passage::offset))
          .limit(2).forEach(result::add);
    }
    return result;
  }

  private void requireConversation(UUID id) {
    Integer count = jdbc.queryForObject("select count(*) from assistant_conversations where id=? and owner_id=?",
        Integer.class, id, access.currentUserId());
    if (count == null || count == 0) throw new IllegalArgumentException("会话不存在或无权访问");
  }

  private String mediaType(String name) {
    if (name.endsWith(".pdf")) return "application/pdf";
    if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if (name.endsWith(".doc")) return "application/msword";
    if (name.endsWith(".txt") || name.endsWith(".md")) return "text/plain";
    throw new IllegalArgumentException("会话文件支持 PDF、Word、TXT、Markdown；其他格式请上传到知识库");
  }

  private String extract(byte[] bytes, String type) throws IOException {
    if ("text/plain".equals(type)) return new String(bytes, StandardCharsets.UTF_8);
    if ("application/pdf".equals(type)) {
      try (var pdf = Loader.loadPDF(bytes)) {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(1);
        stripper.setEndPage(Math.min(pdf.getNumberOfPages(), 40));
        return stripper.getText(pdf);
      }
    }
    if (type.endsWith("wordprocessingml.document")) {
      try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes)); var extractor = new XWPFWordExtractor(doc)) {
        return extractor.getText();
      }
    }
    try (var doc = new HWPFDocument(new ByteArrayInputStream(bytes)); var extractor = new WordExtractor(doc)) {
      return extractor.getText();
    }
  }

  private Set<String> terms(String question) {
    String query = Objects.toString(question, "").toLowerCase(Locale.ROOT);
    Set<String> result = new LinkedHashSet<>();
    for (String word : query.split("[^\\p{IsHan}a-z0-9]+")) {
      if (word.length() >= 2 && word.length() <= 24) result.add(word);
      for (int i = 0; i + 2 <= word.length() && i < 24; i++) result.add(word.substring(i, i + 2));
    }
    return result;
  }

  private record Stored(UUID id, String name, String text) { }
  public record AttachmentView(UUID id, String originalName, String mediaType, long sizeBytes, boolean truncated, Instant createdAt) { }
  public record Passage(UUID attachmentId, String name, int offset, String excerpt, int score) { }
}
