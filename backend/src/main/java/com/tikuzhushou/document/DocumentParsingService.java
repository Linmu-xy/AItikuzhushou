package com.tikuzhushou.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.ocr.VisionOcrService;
import com.tikuzhushou.retrieval.EmbeddingService;
import com.tikuzhushou.retrieval.PostgresVectorStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Page-aware extraction. Weak pages are OCRed individually and never accepted as good text silently. */
@Service
public class DocumentParsingService {
  private static final Pattern PAGE_MARKER = Pattern.compile("\\[第(\\d+)页]");
  private static final ProgressListener NOOP = (stage, progress, processed, total, message) -> { };
  private final DocumentIntakeService intake;
  private final JdbcTemplate jdbc;
  private final EmbeddingService embeddings;
  private final ObjectMapper json;
  private final VisionOcrService ocr;
  private final boolean visionTableEnabled;
  private final PostgresVectorStore vectors;
  private final ObjectStorageService storage;
  private final int ocrMaxPages;
  private final int tableDpi;
  private final Map<UUID, ParsedDocument> parsed = new ConcurrentHashMap<>();

  public DocumentParsingService(DocumentIntakeService intake, JdbcTemplate jdbc,
      EmbeddingService embeddings, ObjectMapper json, VisionOcrService ocr,
      PostgresVectorStore vectors, ObjectStorageService storage,
      @Value("${app.ocr.max-pages:120}") int ocrMaxPages,
      @Value("${app.ocr.table.enabled:false}") boolean visionTableEnabled,
      @Value("${app.ocr.table.dpi:300}") int tableDpi) {
    this.intake = intake;
    this.jdbc = jdbc;
    this.embeddings = embeddings;
    this.json = json;
    this.ocr = ocr;
    this.visionTableEnabled = visionTableEnabled;
    this.vectors = vectors;
    this.storage = storage;
    this.ocrMaxPages = Math.max(1, ocrMaxPages);
    this.tableDpi = Math.max(220, Math.min(tableDpi, 360));
  }

  public ParsedDocument parse(UUID id) throws IOException { return parse(id, NOOP); }

  public ParsedDocument parse(UUID id, ProgressListener progress) throws IOException {
    progress.update("UPLOAD_VERIFYING", 3, 0, 0, "正在校验对象存储中的文件");
    var source = intake.get(id);
    boolean temporary = source.storagePath().startsWith("minio://");
    Path path;
    try {
      path = storage.materialize(source.storagePath(), source.name());
    } catch (Exception e) {
      throw new IOException("对象存储读取失败", e);
    }
    try {
      progress.update("TEXT_EXTRACTING", 6, 0, 0, "正在检测文档文字层");
      Extraction extraction = extract(path, source.mediaType(), progress);
      progress.update("TEXT_CLEANING", 70, 0, 0, "正在清理 OCR 和文字层结果");
      double quality = textQuality(extraction.text());
      // A page with an unresolved OCR/table marker is never silently promoted to a usable standard.
      // High text quality cannot prove that a lost table cell or continuation heading is correct.
      boolean acceptable = quality >= 0.72 && extraction.unresolvedPages() == 0;
      String status = acceptable ? "PARSED" : "OCR_REQUIRED";
      List<String> warnings = new ArrayList<>(extraction.warnings());
      if ("OCR_REQUIRED".equals(status)) warnings.add("仍有较多页面文字不足或不可辨认，禁止直接生成职业标准");
      progress.update("CHAPTER_DETECTING", 76, 0, 0, "正在识别标题和章节层级");
      String markdown = canonicalMarkdown(extraction.text());
      var result = fromText(id, status, markdown, warnings, extraction.tables(), Instant.now());
      progress.update("CHUNKING", 82, result.chunks().size(), result.chunks().size(), "已完成语义分块");
      jdbc.update("update source_documents set extracted_text=?,status=?,parse_quality=?,parse_warnings=?,updated_at=? where id=?",
          markdown, result.status(), result.textQuality(), json.writeValueAsString(result.warnings()),
          java.sql.Timestamp.from(result.parsedAt()), id);
      persistTables(result);
      persistPages(id, markdown, result.parsedAt());
      persistChunks(result, progress);
      parsed.put(id, result);
      progress.update("QUALITY_CHECKING", 99, result.chunks().size(), result.chunks().size(), "正在执行解析质量校验");
      return result;
    } finally {
      if (temporary) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
  }

  public ParsedDocument get(UUID id) {
    var value = parsed.get(id);
    if (value != null) return value;
    var rows = jdbc.query("select extracted_text,status,parse_quality,parse_warnings,updated_at from source_documents where id=?",
        (rs, n) -> new Stored(rs.getString("extracted_text"), rs.getString("status"), rs.getDouble("parse_quality"),
            rs.getString("parse_warnings"), rs.getTimestamp("updated_at")), id);
    if (rows.isEmpty() || rows.getFirst().text() == null) throw new IllegalArgumentException("文档尚未解析，请先调用 /parse");
    var row = rows.getFirst();
    List<String> warnings;
    try { warnings = row.warnings() == null ? List.of() : json.readValue(row.warnings(), new com.fasterxml.jackson.core.type.TypeReference<>() { }); }
    catch (Exception ignored) { warnings = List.of("历史解析警告数据无法读取"); }
    var rebuilt = fromText(id, row.status(), row.text(), warnings, loadTables(id), row.updatedAt().toInstant());
    parsed.put(id, rebuilt);
    return rebuilt;
  }

  public List<EditableChunk> chunks(UUID documentId, int offset, int limit) {
    int safeOffset = Math.max(0, offset), safeLimit = Math.max(1, Math.min(limit, 100));
    intake.get(documentId);
    return jdbc.query("select id,chunk_index,content,source_ref,embedding_model,content_type,metadata_json,created_at from document_chunks where document_id=? order by chunk_index limit ? offset ?",
        (rs, n) -> new EditableChunk(UUID.fromString(rs.getString("id")), rs.getInt("chunk_index"),
            rs.getString("content"), rs.getString("source_ref"), rs.getString("embedding_model"),
            rs.getString("content_type"), rs.getString("metadata_json"),
            rs.getTimestamp("created_at").toInstant()), documentId, safeLimit, safeOffset);
  }

  public EditableChunk updateChunk(UUID documentId, UUID chunkId, String content) {
    if (content == null || content.isBlank()) throw new IllegalArgumentException("片段内容不能为空");
    if (content.length() > 20_000) throw new IllegalArgumentException("单个片段不能超过 20000 字符");
    intake.get(documentId);
    try {
      float[] vector = embeddings.embed(content);
      String sql = postgres()
          ? "update document_chunks set content=?,embedding=cast(? as jsonb),embedding_model=? where id=? and document_id=?"
          : "update document_chunks set content=?,embedding=?,embedding_model=? where id=? and document_id=?";
      int updated = jdbc.update(sql, content.trim(), json.writeValueAsString(vector), embeddings.model(), chunkId, documentId);
      if (updated == 0) throw new IllegalArgumentException("片段不存在");
      try { vectors.save(chunkId, vector); } catch (Exception ignored) { }
      var row = jdbc.query("select id,chunk_index,content,source_ref,embedding_model,content_type,metadata_json,created_at from document_chunks where id=?",
          (rs, n) -> new EditableChunk(UUID.fromString(rs.getString("id")), rs.getInt("chunk_index"),
              rs.getString("content"), rs.getString("source_ref"), rs.getString("embedding_model"),
              rs.getString("content_type"), rs.getString("metadata_json"),
              rs.getTimestamp("created_at").toInstant()), chunkId).getFirst();
      parsed.remove(documentId);
      return row;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("更新片段失败", e);
    }
  }

  public List<ParsedTable> tables(UUID documentId) { intake.get(documentId); return loadTables(documentId); }

  public List<MarkdownPage> pages(UUID documentId) {
    intake.get(documentId);
    List<MarkdownPage> pages = loadPages(documentId);
    if (!pages.isEmpty()) return pages;
    ParsedDocument document = get(documentId);
    persistPages(documentId, document.fullText(), document.parsedAt());
    return loadPages(documentId);
  }

  /** Stable preview model: reading UI consumes blocks, never lossy Markdown tables. */
  public PageContent pageContent(UUID documentId, int page) {
    MarkdownPage source = pages(documentId).stream().filter(item -> item.page() == page).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("页面不存在"));
    Map<Integer, ParsedTable> pageTables = new java.util.HashMap<>();
    for (ParsedTable table : tables(documentId)) if (table.page() == page) pageTables.put(table.tableIndex(), table);
    List<PageBlock> blocks = new ArrayList<>();
    String[] lines = source.markdown().replace("\r", "").split("\n", -1);
    StringBuilder paragraph = new StringBuilder();
    Runnable flush = () -> { if (!paragraph.isEmpty()) { blocks.add(new PageBlock("PARAGRAPH", paragraph.toString().trim(), 0, null, null)); paragraph.setLength(0); } };
    for (String raw : lines) {
      String line = raw.trim();
      if (line.isBlank() || PAGE_MARKER.matcher(line).matches()) { flush.run(); continue; }
      Matcher tableMarker = Pattern.compile("^\\[结构化表格:(\\d+)]$").matcher(line);
      if (tableMarker.matches()) {
        flush.run(); int index = Integer.parseInt(tableMarker.group(1)); ParsedTable table = pageTables.get(index);
        blocks.add(new PageBlock("TABLE", "", 0, table == null ? null : table.id(), index)); continue;
      }
      Matcher heading = Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(line);
      if (heading.matches()) { flush.run(); blocks.add(new PageBlock("HEADING", heading.group(2), heading.group(1).length(), null, null)); continue; }
      if (line.matches("^[-*]\\s+.+") || line.matches("^\\d+[.)、]\\s+.+")) {
        flush.run(); blocks.add(new PageBlock("LIST_ITEM", line.replaceFirst("^(?:[-*]|\\d+[.)、])\\s+", ""), 0, null, null)); continue;
      }
      if (!paragraph.isEmpty()) paragraph.append('\n'); paragraph.append(line);
    }
    flush.run();
    return new PageContent(page, List.copyOf(blocks), source.reviewStatus(), source.version(), source.updatedAt());
  }

  public List<PageVersion> pageVersions(UUID documentId, int page) {
    intake.get(documentId);
    MarkdownPage current = pages(documentId).stream().filter(item -> item.page() == page).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("页面不存在"));
    return jdbc.query("select version,markdown,review_status,action,actor,created_at from document_page_versions where document_page_id=? order by version desc",
        (rs, row) -> new PageVersion(rs.getInt("version"), rs.getString("markdown"),
            rs.getString("review_status"), rs.getString("action"), rs.getString("actor"),
            rs.getTimestamp("created_at").toInstant()), current.id());
  }

  public PageUpdateResult updatePage(UUID documentId, int page, String markdown,
      boolean confirmed, boolean rebuildVectors, String actor) {
    if (markdown == null || markdown.isBlank()) throw new IllegalArgumentException("页面 Markdown 不能为空");
    if (markdown.length() > 200_000) throw new IllegalArgumentException("单页 Markdown 不能超过 200000 字符");
    intake.get(documentId);
    MarkdownPage current = pages(documentId).stream().filter(item -> item.page() == page).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("页面不存在"));
    String normalized = ensurePageMarker(page, markdown);
    if (!confirmed) throw new IllegalArgumentException("PAGE_CONFIRMATION_REQUIRED：请先确认人工修改内容");
    int nextVersion = current.version() + 1; Instant now = Instant.now();
    String status = "HUMAN_CONFIRMED";
    int updated = jdbc.update("update document_pages set markdown=?,review_status=?,version=?,active=true,confirmed_by=?,updated_at=? where id=? and document_id=?",
        normalized, status, nextVersion, actor, java.sql.Timestamp.from(now), current.id(), documentId);
    if (updated == 0) throw new IllegalArgumentException("页面不存在或无权编辑");
    jdbc.update("insert into document_page_versions(id,document_page_id,version,markdown,review_status,action,actor,created_at) values(?,?,?,?,?,?,?,?)",
        UUID.randomUUID(), current.id(), nextVersion, normalized, status, "HUMAN_CONFIRM", actor,
        java.sql.Timestamp.from(now));
    String fullMarkdown = loadPages(documentId).stream().map(MarkdownPage::markdown)
        .collect(java.util.stream.Collectors.joining("\n\n"));
    jdbc.update("update source_documents set extracted_text=?,updated_at=? where id=?",
        fullMarkdown, java.sql.Timestamp.from(now), documentId);
    parsed.remove(documentId);
    int chunks = 0;
    if (rebuildVectors) {
      ParsedDocument rebuilt = get(documentId);
      persistChunks(rebuilt, NOOP);
      chunks = rebuilt.chunks().size();
    }
    MarkdownPage saved = loadPages(documentId).stream().filter(item -> item.page() == page).findFirst().orElseThrow();
    return new PageUpdateResult(saved, rebuildVectors, chunks);
  }

  public ParsedTable updateTable(UUID documentId, UUID tableId, String markdown) {
    if (markdown == null || markdown.isBlank()) throw new IllegalArgumentException("表格内容不能为空");
    if (markdown.length() > 100_000) throw new IllegalArgumentException("单个表格不能超过 100000 字符");
    intake.get(documentId);
    ParsedTable current = loadTables(documentId).stream().filter(table -> table.id().equals(tableId)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("表格不存在"));
    List<ParsedTable> parsedTables = parseMarkdownTables(markdown, current.page());
    if (parsedTables.size() != 1) throw new IllegalArgumentException("请保留一个完整 Markdown 表格，不能包含多个表格");
    ParsedTable parsedTable = parsedTables.getFirst(); Instant now = Instant.now();
    try {
      jdbc.update("update document_tables set title=?,headers_json=?,rows_json=?,markdown=?,confidence=?,review_status='HUMAN_EDITED',warnings_json=?,structure_html=?,table_bbox_json=?,extraction_source='HUMAN_MARKDOWN',updated_at=? where id=? and document_id=?",
          current.title(), json.writeValueAsString(parsedTable.headers()), json.writeValueAsString(parsedTable.rows()),
          markdown.trim(), 1d, json.writeValueAsString(List.of("已由人工编辑确认；Markdown 编辑无法保留原合并单元格结构")),
          "", json.writeValueAsString(List.of()),
          java.sql.Timestamp.from(now), tableId, documentId);
      jdbc.update("delete from document_table_cells where table_id=?", tableId);
      ParsedTable human = new ParsedTable(tableId, current.page(), current.tableIndex(), current.title(), parsedTable.headers(),
          parsedTable.rows(), markdown.trim(), 1d, "HUMAN_EDITED", List.of(), now, "",
          cellsFromGrid(parsedTable.headers(), parsedTable.rows(), "HUMAN_MARKDOWN"), List.of(), "HUMAN_MARKDOWN");
      saveTableCells(human);
      parsed.remove(documentId);
      ParsedDocument rebuilt = get(documentId);
      persistChunks(rebuilt, NOOP);
      return loadTables(documentId).stream().filter(table -> table.id().equals(tableId)).findFirst().orElseThrow();
    } catch (IllegalArgumentException error) { throw error; }
    catch (Exception error) { throw new IllegalStateException("保存表格并重建向量失败", error); }
  }

  public byte[] renderPage(UUID documentId, int page) {
    return renderPage(documentId, page, 150);
  }

  /** Higher resolution is used only when a drawing is inspected for an assessment item. */
  public byte[] renderPage(UUID documentId, int page, int dpi) {
    var source = intake.get(documentId);
    if (!"application/pdf".equals(source.mediaType())) throw new IllegalArgumentException("只有 PDF 支持原页图像预览");
    boolean temporary = source.storagePath().startsWith("minio://"); Path path;
    try { path = storage.materialize(source.storagePath(), source.name()); }
    catch (Exception error) { throw new IllegalStateException("对象存储读取失败", error); }
    try (var pdf = Loader.loadPDF(path.toFile()); var output = new ByteArrayOutputStream()) {
      if (page < 1 || page > pdf.getNumberOfPages()) throw new IllegalArgumentException("页码超出 PDF 范围");
      BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(page - 1, Math.max(100, Math.min(300, dpi)));
      ImageIO.write(image, "png", output); return output.toByteArray();
    } catch (IllegalArgumentException error) { throw error; }
    catch (Exception error) { throw new IllegalStateException("渲染 PDF 原页失败", error); }
    finally { if (temporary) try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
  }

  private Extraction extract(Path path, String type, ProgressListener progress) throws IOException {
    try {
      if ("application/pdf".equals(type)) return extractPdf(path, progress);
      if (type != null && type.startsWith("image/")) {
        byte[] image = Files.readAllBytes(path); String mime = normalizeImageMime(type);
        List<String> warnings = new ArrayList<>();
        BufferedImage decoded = ImageIO.read(path.toFile());
        // Do not call remote vision for ordinary photos or text-only scans.
        // A ruled-table preflight is nearly free and keeps API usage bounded.
        VisionPageResult vision = visionTableEnabled && decoded != null && imageLooksTabular(decoded)
            ? extractVisionTables(1, decoded, "", "", warnings) : VisionPageResult.EMPTY;
        List<ParsedTable> tables = vision.tables();
        if (!tables.isEmpty() && !ocr.enabled()) {
          String text = visionPageMarkdown(1, vision.markdown(), tables);
          return new Extraction(text, goodText(text) ? 0 : 1, List.copyOf(warnings), tables);
        }
        if (!ocr.enabled()) return new Extraction("", 1, List.of("图片文档需要启用 OCR 或表格结构服务"), tables);
        progress.update("OCR_PROCESSING", 35, 1, 1, tables.isEmpty() ? "正在识别图片文字" : "正在识别图片文字并保留表格结构");
        String text = tables.isEmpty() ? ocr.recognizePage(1, List.of(image), mime)
            : visionPageMarkdown(1, vision.markdown(), tables);
        return new Extraction(text, goodText(text) && !vision.needsReview() ? 0 : 1, List.copyOf(warnings),
            tables.isEmpty() ? parseMarkdownTables(text, 1) : tables);
      }
      String text = extractOffice(path, type);
      return new Extraction(text, goodText(text) ? 0 : 1,
          goodText(text) ? List.of() : List.of("文档可提取文字不足"), parseMarkdownTables(text, 1));
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("文档解析失败：" + e.getMessage(), e);
    }
  }

  private Extraction extractPdf(Path path, ProgressListener progress) throws IOException {
    try (var pdf = Loader.loadPDF(path.toFile())) {
      PDFRenderer renderer = new PDFRenderer(pdf);
      StringBuilder all = new StringBuilder();
      List<String> warnings = new ArrayList<>();
      List<ParsedTable> tables = new ArrayList<>();
      int unresolved = 0;
      for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
        int pageProgress = 8 + (int) Math.round(58d * (page - 1) / Math.max(1, pdf.getNumberOfPages()));
        progress.update("TEXT_EXTRACTING", pageProgress, page, pdf.getNumberOfPages(),
            "正在检查第 " + page + " / " + pdf.getNumberOfPages() + " 页文字层");
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String raw = stripper.getText(pdf);
        String local = cleanExtractedText(raw);
        VisionPageResult vision = VisionPageResult.EMPTY;
        BufferedImage layoutPreview = null;
        boolean structureAttempted = false;
        // Some source PDFs contain an intentional or accidental blank trailing page. A blank
        // page cannot produce OCR text, so sending it to vision would create a false quality
        // failure for an otherwise complete document. Never apply this to the first page: an
        // entirely blank upload must still be rejected instead of silently accepted.
        boolean priorContent = !all.isEmpty() || !tables.isEmpty();
        if (page > 1 && priorContent && local.isBlank()) {
          layoutPreview = renderer.renderImageWithDPI(page - 1, 110);
          if (isVisuallyBlank(layoutPreview)) {
            all.append("[第").append(page).append("页]\n");
            warnings.add("第" + page + "页为空白页，已跳过 OCR");
            continue;
          }
        }
        // Native PDFs are handled from their text layer first. Only pages with
        // textual table evidence or an actual low-resolution grid pay for vision.
        boolean structureCandidate = visionTableEnabled && ocr.enabled() && looksLikeTable(raw);
        if (visionTableEnabled && ocr.enabled() && !structureCandidate) {
          layoutPreview = renderer.renderImageWithDPI(page - 1, 110);
          structureCandidate = imageLooksTabular(layoutPreview);
        }
        if (structureCandidate) {
          structureAttempted = true;
          progress.update("OCR_RENDERING", pageProgress, page, pdf.getNumberOfPages(),
              "正在定位第 " + page + " 页表格单元格");
          BufferedImage tableImage = renderer.renderImageWithDPI(page - 1, tableDpi);
          vision = extractVisionTables(page, tableImage, local, readingContext(all, tables, page - 1), warnings);
        }
        if (vision.needsReview()) unresolved++;
        List<ParsedTable> extractedTables = vision.tables();
        if (!extractedTables.isEmpty()) {
          List<ParsedTable> pageTables = stitchReadingContinuation(extractedTables, tables);
          all.append(visionPageMarkdown(page, vision.markdown(), pageTables)).append('\n');
          tables.addAll(pageTables); pageTables.forEach(table -> warnings.addAll(table.warnings()));
          continue;
        }
        if (goodText(local)) {
          all.append("[第").append(page).append("页]\n").append(local).append('\n');
          List<ParsedTable> pageTables = parseMarkdownTables(local, page);
          tables.addAll(pageTables);
          continue;
        }
        if (!ocr.enabled() || page > ocrMaxPages) {
          unresolved++;
          if (!local.isBlank()) all.append("[第").append(page).append("页]\n").append(local).append('\n');
          warnings.add("第" + page + "页文字层质量不足且未完成 OCR");
          continue;
        }
        progress.update("OCR_RENDERING", pageProgress, page, pdf.getNumberOfPages(),
            "正在低分辨率检查第 " + page + " / " + pdf.getNumberOfPages() + " 页版式");
        BufferedImage preview = layoutPreview == null ? renderer.renderImageWithDPI(page - 1, 110) : layoutPreview;
        boolean scannedTableCandidate = visionTableEnabled && ocr.enabled() && !structureAttempted && imageLooksTabular(preview);
        VisionPageResult scannedVision = VisionPageResult.EMPTY;
        if (scannedTableCandidate) {
          progress.update("OCR_RENDERING", pageProgress, page, pdf.getNumberOfPages(),
              "检测到表格，正在提取第 " + page + " 页单元格结构");
          BufferedImage tableImage = renderer.renderImageWithDPI(page - 1, tableDpi);
          scannedVision = extractVisionTables(page, tableImage, local, readingContext(all, tables, page - 1), warnings);
        }
        if (scannedVision.needsReview()) unresolved++;
        List<ParsedTable> scannedTables = scannedVision.tables();
        String recognized;
        if (scannedTables.isEmpty()) {
          BufferedImage image = renderer.renderImageWithDPI(page - 1, 220);
          progress.update("OCR_PROCESSING", Math.min(66, pageProgress + 1), page, pdf.getNumberOfPages(),
              "AI 正在识别第 " + page + " / " + pdf.getNumberOfPages() + " 页");
          // Vision normalizes large images internally. JPEG keeps the upload bounded without
          // changing the reading crops supplied to the model.
          recognized = ocr.recognizePage(page, splitPage(image), "image/jpeg");
        } else recognized = visionPageMarkdown(page, scannedVision.markdown(), scannedTables);
        if (!scannedTables.isEmpty()) {
          List<ParsedTable> pageTables = stitchReadingContinuation(scannedTables, tables);
          tables.addAll(pageTables); pageTables.forEach(table -> warnings.addAll(table.warnings()));
        }
        if (!goodText(recognized)) {
          unresolved++;
          warnings.add("第" + page + "页 OCR 后仍存在较多不可辨认内容");
        }
        boolean hasPageTable = false;
        for (ParsedTable table : tables) if (table.page() == page) { hasPageTable = true; break; }
        if (!hasPageTable) {
          List<ParsedTable> pageTables = parseMarkdownTables(recognized, page);
          tables.addAll(pageTables);
        }
        all.append(recognized).append('\n');
      }
      return new Extraction(all.toString().trim(), unresolved, List.copyOf(new LinkedHashSet<>(warnings)), List.copyOf(tables));
    }
  }

  private List<byte[]> splitPage(BufferedImage image) throws IOException {
    int bandHeight = Math.max(900, Math.min(1500, image.getWidth()));
    int overlap = 90;
    List<byte[]> result = new ArrayList<>();
    for (int y = 0; y < image.getHeight(); y += bandHeight - overlap) {
      int height = Math.min(bandHeight, image.getHeight() - y);
      BufferedImage crop = image.getSubimage(0, y, image.getWidth(), height);
      result.add(jpegBytes(crop));
      if (y + height >= image.getHeight()) break;
    }
    return result;
  }

  /** JPEG is materially smaller than a rendered PNG and DeepSeek accepts it natively. */
  private byte[] jpegBytes(BufferedImage image) throws IOException {
    BufferedImage rgb = image;
    if (image.getType() != BufferedImage.TYPE_INT_RGB) {
      rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
      var graphics = rgb.createGraphics();
      try {
        graphics.drawImage(image, 0, 0, null);
      } finally {
        graphics.dispose();
      }
    }
    try (var output = new ByteArrayOutputStream()) {
      if (!ImageIO.write(rgb, "jpeg", output)) throw new IOException("JVM 未安装 JPEG 编码器");
      return output.toByteArray();
    }
  }

  /** The overview only supplies page layout; text is read from the high-detail bands below. */
  private BufferedImage overview(BufferedImage image) {
    int maxSide = 1_000;
    int largest = Math.max(image.getWidth(), image.getHeight());
    if (largest <= maxSide) return image;
    double scale = maxSide / (double) largest;
    int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
    int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
    BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var graphics = resized.createGraphics();
    try {
      graphics.drawImage(image, 0, 0, width, height, null);
    } finally {
      graphics.dispose();
    }
    return resized;
  }

  /** Cheap, local table suspicion check; avoids calling the vision model for ordinary text pages. */
  boolean looksLikeTable(String raw) {
    if (raw == null || raw.isBlank()) return false;
    String[] lines = raw.replace('\r', '\n').split("\\R"); int alignedRows = 0, pipeRows = 0, maxHeaderTermsInLine = 0;
    List<String> headerTerms = List.of("序号", "项目", "职业功能", "工作内容", "技能要求", "相关知识", "鉴定要求",
        "等级", "权重", "比重", "考核内容", "标准学时", "申报条件", "列");
    for (String line : lines) {
      String trimmed = line.trim(); if (trimmed.isBlank()) continue;
      if (trimmed.split("(?:\\t+| {2,})").length >= 3) alignedRows++;
      if (trimmed.chars().filter(value -> value == '|').count() >= 2) pipeRows++;
      int lineHeaderTerms = 0;
      for (String term : headerTerms) if (trimmed.contains(term)) lineHeaderTerms++;
      maxHeaderTermsInLine = Math.max(maxHeaderTermsInLine, lineHeaderTerms);
    }
    // PDFBox can emit each visual column as a separate short line.  Three known
    // headers (for example 工作内容/技能要求/相关知识) are already strong table
    // evidence when spacing between columns is not preserved in the text layer.
    return pipeRows >= 2 || alignedRows >= 3 || (alignedRows >= 1 && maxHeaderTermsInLine >= 2)
        || maxHeaderTermsInLine >= 3;
  }

  /** Detects ruled tables in scanned pages without loading a local AI model. */
  boolean imageLooksTabular(BufferedImage image) {
    int width = image.getWidth(), height = image.getHeight();
    if (width < 100 || height < 100) return false;
    boolean[] horizontal = new boolean[height], vertical = new boolean[width];
    int step = Math.max(1, Math.min(width, height) / 1800);
    for (int y = 0; y < height; y += step) {
      int dark = 0, samples = 0;
      for (int x = 0; x < width; x += step) { if (dark(image.getRGB(x, y))) dark++; samples++; }
      horizontal[y] = dark >= samples * .28;
    }
    for (int x = 0; x < width; x += step) {
      int dark = 0, samples = 0;
      for (int y = 0; y < height; y += step) { if (dark(image.getRGB(x, y))) dark++; samples++; }
      vertical[x] = dark >= samples * .18;
    }
    return groups(horizontal, step) >= 3 && groups(vertical, step) >= 2;
  }

  /**
   * Conservative blank-page detector for PDF renderings. It accepts only an almost perfectly
   * white page, so a page number, stamp, signature, faint scan noise, or sparse real content is
   * still routed through the normal OCR quality checks.
   */
  boolean isVisuallyBlank(BufferedImage image) {
    if (image == null || image.getWidth() < 20 || image.getHeight() < 20) return false;
    int width = image.getWidth(), height = image.getHeight();
    int step = Math.max(1, Math.min(width, height) / 900);
    long samples = 0, nonWhite = 0, darkPixels = 0;
    for (int y = 0; y < height; y += step) for (int x = 0; x < width; x += step) {
      int rgb = image.getRGB(x, y);
      int red = (rgb >> 16) & 255, green = (rgb >> 8) & 255, blue = rgb & 255;
      int luminance = (red * 30 + green * 59 + blue * 11) / 100;
      samples++;
      if (luminance < 250) nonWhite++;
      if (luminance < 230) darkPixels++;
    }
    // Allow only JPEG/rendering speckles; a real page number is much larger than this budget.
    return darkPixels == 0 && nonWhite <= Math.max(12, samples / 200_000);
  }

  private boolean dark(int rgb) {
    int red = (rgb >> 16) & 255, green = (rgb >> 8) & 255, blue = rgb & 255;
    return (red * 30 + green * 59 + blue * 11) / 100 < 145;
  }
  private int groups(boolean[] values, int step) {
    int count = 0; boolean inside = false;
    for (int index = 0; index < values.length; index += step) {
      if (values[index] && !inside) { count++; inside = true; }
      else if (!values[index]) inside = false;
    }
    return count;
  }

  /**
   * Finds the envelope of ruled grid lines.  A conservative full-page fallback is intentional:
   * it is better to spend one image than crop away a column of a table.
   */
  private java.awt.Rectangle tableRegion(BufferedImage source, BufferedImage probe) {
    java.awt.Rectangle detected = tableRegionOnProbe(probe);
    if (detected.x == 0 && detected.y == 0 && detected.width == probe.getWidth() && detected.height == probe.getHeight())
      return new java.awt.Rectangle(0, 0, source.getWidth(), source.getHeight());
    double scaleX = source.getWidth() / (double) probe.getWidth();
    double scaleY = source.getHeight() / (double) probe.getHeight();
    int x = Math.max(0, (int) Math.floor(detected.x * scaleX));
    int y = Math.max(0, (int) Math.floor(detected.y * scaleY));
    int endX = Math.min(source.getWidth(), (int) Math.ceil((detected.x + detected.width) * scaleX));
    int endY = Math.min(source.getHeight(), (int) Math.ceil((detected.y + detected.height) * scaleY));
    return new java.awt.Rectangle(x, y, Math.max(1, endX - x), Math.max(1, endY - y));
  }

  /** Runs grid projection on the 1,000px layout probe, never on the 300-DPI source image. */
  private java.awt.Rectangle tableRegionOnProbe(BufferedImage image) {
    int width = image.getWidth(), height = image.getHeight();
    boolean[] horizontal = new boolean[height], vertical = new boolean[width];
    int step = Math.max(1, Math.min(width, height) / 1800);
    for (int y = 0; y < height; y += step) {
      int dark = 0, samples = 0;
      for (int x = 0; x < width; x += step) { if (dark(image.getRGB(x, y))) dark++; samples++; }
      horizontal[y] = dark >= samples * .28;
    }
    for (int x = 0; x < width; x += step) {
      int dark = 0, samples = 0;
      for (int y = 0; y < height; y += step) { if (dark(image.getRGB(x, y))) dark++; samples++; }
      vertical[x] = dark >= samples * .18;
    }
    if (groups(horizontal, step) < 3 || groups(vertical, step) < 2) return new java.awt.Rectangle(0, 0, width, height);
    int top = firstSignal(horizontal, step), bottom = lastSignal(horizontal, step);
    int left = firstSignal(vertical, step), right = lastSignal(vertical, step);
    if (top < 0 || left < 0 || bottom <= top || right <= left) return new java.awt.Rectangle(0, 0, width, height);
    int margin = Math.max(20, Math.min(width, height) / 100);
    int x = Math.max(0, left - margin), y = Math.max(0, top - margin);
    int endX = Math.min(width, right + margin + step), endY = Math.min(height, bottom + margin + step);
    if (endX - x < width * .35 || endY - y < height * .08) return new java.awt.Rectangle(0, 0, width, height);
    return new java.awt.Rectangle(x, y, endX - x, endY - y);
  }

  private int firstSignal(boolean[] values, int step) {
    for (int index = 0; index < values.length; index += step) if (values[index]) return index;
    return -1;
  }

  private int lastSignal(boolean[] values, int step) {
    for (int index = ((values.length - 1) / step) * step; index >= 0; index -= step) if (values[index]) return index;
    return -1;
  }

  /** Overview plus one ROI, or two overlapping ROI bands for a tall table. */
  private List<byte[]> tableViews(BufferedImage image) throws IOException {
    BufferedImage layout = overview(image);
    java.awt.Rectangle region = tableRegion(image, layout);
    boolean fullPage = region.x == 0 && region.y == 0 && region.width == image.getWidth() && region.height == image.getHeight();
    if (fullPage) return List.of(jpegBytes(image));
    BufferedImage crop = image.getSubimage(region.x, region.y, region.width, region.height);
    if (crop.getHeight() <= crop.getWidth() * 1.65) return List.of(jpegBytes(layout), jpegBytes(crop));
    int overlap = Math.min(120, Math.max(40, crop.getHeight() / 20));
    int bandHeight = (crop.getHeight() + overlap) / 2;
    BufferedImage top = crop.getSubimage(0, 0, crop.getWidth(), bandHeight);
    BufferedImage bottom = crop.getSubimage(0, crop.getHeight() - bandHeight, crop.getWidth(), bandHeight);
    return List.of(jpegBytes(layout), jpegBytes(top), jpegBytes(bottom));
  }

  private List<ParsedTable> fromVisionTables(int page, List<VisionOcrService.StructuredTable> tables) {
    List<ParsedTable> result = new ArrayList<>(); int index = 0;
    for (var table : tables) {
      List<TableCell> cells = table.cells().stream().map(cell -> new TableCell(UUID.randomUUID(), cell.row(), cell.column(),
          cell.rowSpan(), cell.columnSpan(), cell.text(), cell.confidence(), List.of(), "DEEPSEEK_VISION")).toList();
      result.add(new ParsedTable(UUID.randomUUID(), page, index++, table.title(), table.headers(), table.rows(),
          tableMarkdown(table.title(), table.headers(), table.rows()), table.confidence(), "AI_EXTRACTED", table.warnings(), Instant.now(),
          "", cells, List.of(), "DEEPSEEK_VISION"));
    }
    return result;
  }

  /** Uses remote vision only for a page already selected by the lightweight preflight. */
  private VisionPageResult extractVisionTables(int page, BufferedImage image, String textLayer,
      String previousPageContext, List<String> warnings) {
    if (!visionTableEnabled || !ocr.enabled()) return VisionPageResult.EMPTY;
    List<byte[]> views = null;
    try {
      views = tableViews(image);
      VisionOcrService.TableRegionResult vision = ocr.recognizeTableRegions(page, views, "image/jpeg", textLayer, previousPageContext);
      List<ParsedTable> tables = fromVisionTables(page, vision.tables());
      if (tables.isEmpty()) throw new IllegalArgumentException("单元格响应没有可解析的表格");
      return new VisionPageResult(pageWithStructuredTables(page, vision.body(), tables), tables, false);
    } catch (Exception error) {
      // Large or merged-cell tables can exceed the structured JSON budget or produce an
      // overlapping cell grid.  Do not accept that invalid grid, but preserve a usable
      // reading projection when the model can reconstruct a complete Markdown table.
      try {
        if (views == null) views = tableViews(image);
        String reading = ocr.recognizeReadingPage(page, views, "image/jpeg", textLayer, previousPageContext);
        List<ParsedTable> tables = parseMarkdownTables(reading, page, "DEEPSEEK_READING", .78);
        if (tables.isEmpty()) throw new IllegalArgumentException("阅读式 OCR 没有返回完整 Markdown 表格");
        String reviewWarning = "结构化单元格识别未通过，已采用阅读式 OCR 回退；合并单元格和跨页表头请人工确认";
        List<ParsedTable> reviewTables = addTableWarning(tables, reviewWarning);
        warnings.add("第" + page + "页表格单元格结构识别失败（" + concise(error)
            + "），已改用阅读式 OCR 提取并标记为待人工复核");
        return new VisionPageResult(markdownWithTableMarkers(page, reading), reviewTables, false);
      } catch (Exception fallbackError) {
        warnings.add("第" + page + "页表格区域识别失败，已标记为待人工复核："
            + concise(error) + "；阅读式 OCR 回退失败：" + concise(fallbackError));
        return VisionPageResult.FAILED;
      }
    }
  }

  /** Keeps the fallback visible in the table review UI instead of only in document-level warnings. */
  private List<ParsedTable> addTableWarning(List<ParsedTable> tables, String warning) {
    return tables.stream().map(table -> {
      List<String> tableWarnings = new ArrayList<>(table.warnings());
      tableWarnings.add(warning);
      return new ParsedTable(table.id(), table.page(), table.tableIndex(), table.title(), table.headers(), table.rows(),
          table.markdown(), table.confidence(), table.reviewStatus(), List.copyOf(tableWarnings), table.updatedAt(),
          table.structureHtml(), table.cells(), table.boundingBox(), table.extractionSource());
    }).toList();
  }

  /** Provides cross-page continuity without retaining additional rendered images in memory. */
  private String readingContext(StringBuilder document, List<ParsedTable> tables, int previousPage) {
    String value = document == null ? "" : document.toString().trim();
    int start = Math.max(0, value.length() - 6_000);
    StringBuilder context = new StringBuilder(value.substring(start));
    for (ParsedTable table : tables) if (table.page() == previousPage) {
      context.append("\n\n上一页表格 ").append(table.tableIndex() + 1).append("：\n").append(table.markdown());
    }
    String result = context.toString();
    return result.length() <= 12_000 ? result : result.substring(result.length() - 12_000);
  }

  /** Joins category labels physically cut by a PDF page boundary for the reading projection. */
  private List<ParsedTable> stitchReadingContinuation(List<ParsedTable> current, List<ParsedTable> prior) {
    if (current.isEmpty() || prior.isEmpty()) return current;
    ParsedTable previous = null;
    int page = current.getFirst().page();
    for (int index = prior.size() - 1; index >= 0; index--) {
      if (prior.get(index).page() == page - 1) { previous = prior.get(index); break; }
      if (prior.get(index).page() < page - 1) break;
    }
    ParsedTable first = current.getFirst();
    if (previous == null || first.rows().isEmpty() || first.headers().isEmpty()) return current;
    String priorLabel = "";
    for (int index = previous.rows().size() - 1; index >= 0; index--) {
      if (!previous.rows().get(index).isEmpty() && !previous.rows().get(index).getFirst().isBlank()) {
        priorLabel = cleanReadingCell(previous.rows().get(index).getFirst()); break;
      }
    }
    String currentLabel = first.rows().getFirst().isEmpty() ? "" : cleanReadingCell(first.rows().getFirst().getFirst());
    if (priorLabel.isBlank() || !priorLabel.matches("^[一二三四五六七八九十百]+、.+")) return current;
    String stitched = continuationLabel(priorLabel, currentLabel);
    if (stitched.isBlank()) return current;
    List<List<String>> rows = first.rows().stream().map(ArrayList::new).map(List::copyOf).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    List<String> firstRow = new ArrayList<>(rows.getFirst());
    firstRow.set(0, stitched);
    rows.set(0, List.copyOf(firstRow));
    ParsedTable corrected = new ParsedTable(first.id(), first.page(), first.tableIndex(), first.title(), first.headers(),
        List.copyOf(rows), tableMarkdown(first.title(), first.headers(), rows), first.confidence(), first.reviewStatus(),
        first.warnings(), first.updatedAt(), first.structureHtml(), cellsFromGrid(first.headers(), rows, "DEEPSEEK_READING"),
        first.boundingBox(), first.extractionSource());
    List<ParsedTable> result = new ArrayList<>(current); result.set(0, corrected); return List.copyOf(result);
  }

  private String cleanReadingCell(String value) {
    return Objects.toString(value, "").replaceAll("(?i)<br\\s*/?>", "").replaceAll("\\s+", "").trim();
  }

  private String continuationLabel(String previous, String current) {
    String suffix = "（续）";
    previous = previous.replace(suffix, ""); current = current.replace(suffix, "");
    if (current.isBlank()) return previous + suffix;
    if (current.matches("^[一二三四五六七八九十百]+、.+")
        && !current.startsWith(previous) && !previous.startsWith(current)) return "";
    if (current.startsWith(previous)) return current + suffix;
    if (previous.startsWith(current)) return previous + suffix;
    int overlap = Math.min(previous.length(), current.length());
    while (overlap > 0 && !previous.endsWith(current.substring(0, overlap))) overlap--;
    String joined = previous + current.substring(overlap);
    return joined.length() <= 40 ? joined + suffix : "";
  }

  /** Replaces generated GFM tables with safe persisted-table references for the reading UI. */
  private String markdownWithTableMarkers(int page, String markdown) {
    String[] lines = Objects.toString(markdown, "").replace("\r", "").split("\n", -1);
    StringBuilder result = new StringBuilder(); int tableIndex = 0;
    for (int index = 0; index < lines.length;) {
      if (index + 1 < lines.length && lines[index].contains("|")
          && lines[index + 1].matches("\\s*\\|?\\s*:?-{2,}.*\\|.*")) {
        result.append("[结构化表格:").append(tableIndex++).append("]\n");
        index += 2;
        while (index < lines.length && lines[index].contains("|")) index++;
      } else {
        result.append(lines[index]).append('\n'); index++;
      }
    }
    return ensurePageMarker(page, result.toString());
  }

  private String visionPageMarkdown(int page, String markdown, List<ParsedTable> tables) {
    String value = ensurePageMarker(page, markdown);
    for (ParsedTable table : tables) value = value.replace("[TABLE_" + table.tableIndex() + "]",
        "[结构化表格:" + table.tableIndex() + "]");
    for (ParsedTable table : tables) if (!value.contains("[结构化表格:" + table.tableIndex() + "]"))
      value += "\n\n[结构化表格:" + table.tableIndex() + "]";
    return value.trim();
  }

  private String pageWithStructuredTables(int page, String body, List<ParsedTable> tables) {
    StringBuilder value = new StringBuilder(ensurePageMarker(page, body));
    // Markdown tables cannot express merged cells.  Keep a stable, safe marker
    // in the page text; the reader resolves it to the persisted cell grid and
    // renders real rowspan/colspan values instead of flattening the layout.
    for (ParsedTable table : tables) value.append("\n\n[结构化表格:").append(table.tableIndex()).append("]");
    return value.toString().trim();
  }

  private List<ParsedTable> inheritContinuationHeaders(int page, List<ParsedTable> current,
      List<ParsedTable> priorTables) {
    if (current.isEmpty() || priorTables.isEmpty()) return current;
    ParsedTable previous = null;
    for (int index = priorTables.size() - 1; index >= 0; index--) {
      ParsedTable candidate = priorTables.get(index);
      if (candidate.page() == page - 1) { previous = candidate; break; }
      if (candidate.page() < page - 1) break;
    }
    if (previous == null) return current;
    List<ParsedTable> result = new ArrayList<>();
    for (ParsedTable table : current) {
      if (genericHeaders(table.headers()) && table.headers().size() == previous.headers().size()) {
        List<String> inheritedWarnings = new ArrayList<>(table.warnings());
        inheritedWarnings.add("第" + page + "页为跨页续表，已沿用第" + previous.page() + "页表头，建议人工确认");
        result.add(new ParsedTable(table.id(), table.page(), table.tableIndex(), table.title(),
            previous.headers(), table.rows(), tableMarkdown(table.title(), previous.headers(), table.rows()),
            table.confidence(), table.reviewStatus(), List.copyOf(inheritedWarnings), table.updatedAt(),
            table.structureHtml(), table.cells(), table.boundingBox(), table.extractionSource()));
      } else {
        result.add(table);
      }
    }
    return result;
  }

  private boolean genericHeaders(List<String> headers) {
    return !headers.isEmpty() && headers.stream().allMatch(value -> value != null && value.matches("列\\d+"));
  }

  private String normalizeStructuredMarkdown(int page, String markdown, List<ParsedTable> tables) {
    if (tables.isEmpty()) return ensurePageMarker(page, markdown);
    String[] lines = Objects.toString(markdown, "").replace("\r", "").split("\n", -1);
    StringBuilder result = new StringBuilder(); int tableIndex = 0;
    for (int index = 0; index < lines.length;) {
      if (index + 1 < lines.length && lines[index].contains("|")
          && lines[index + 1].matches("\\s*\\|?\\s*:?-{2,}.*\\|.*") && tableIndex < tables.size()) {
        result.append(tableMarkdown(tables.get(tableIndex).title(), tables.get(tableIndex).headers(),
            tables.get(tableIndex).rows())).append("\n\n");
        tableIndex++; index += 2;
        while (index < lines.length && lines[index].trim().startsWith("|")) index++;
      } else {
        result.append(lines[index]).append('\n'); index++;
      }
    }
    while (tableIndex < tables.size()) {
      result.append('\n').append(tableMarkdown(tables.get(tableIndex).title(), tables.get(tableIndex).headers(),
          tables.get(tableIndex).rows())).append('\n'); tableIndex++;
    }
    return ensurePageMarker(page, result.toString());
  }

  List<ParsedTable> parseMarkdownTables(String markdown, int page) {
    return parseMarkdownTables(markdown, page, "MARKDOWN_DETECTED", .68);
  }

  private List<ParsedTable> parseMarkdownTables(String markdown, int page, String source, double confidence) {
    if (markdown == null || markdown.isBlank()) return List.of(); String[] lines = markdown.split("\\R");
    List<ParsedTable> result = new ArrayList<>(); int tableIndex = 0;
    for (int index = 0; index + 1 < lines.length; index++) {
      if (!lines[index].contains("|") || !lines[index + 1].matches("\\s*\\|?\\s*:?-{2,}.*\\|.*")) continue;
      List<String> headers = markdownCells(lines[index]); List<List<String>> rows = new ArrayList<>();
      int cursor = index + 2;
      while (cursor < lines.length && lines[cursor].contains("|")) { rows.add(markdownCells(lines[cursor])); cursor++; }
      int columns = Math.max(headers.size(), rows.stream().mapToInt(List::size).max().orElse(0));
      if (columns >= 2 && !rows.isEmpty()) {
        headers = padCells(headers, columns); List<List<String>> normalized = new ArrayList<>();
        for (List<String> row : rows) normalized.add(padCells(row, columns));
        String tableText = String.join("\n", Arrays.copyOfRange(lines, index, cursor));
        String reviewStatus = "DEEPSEEK_READING".equals(source) ? "AI_EXTRACTED" : "MARKDOWN_DETECTED";
        result.add(new ParsedTable(UUID.randomUUID(), page, tableIndex++, "", List.copyOf(headers),
            List.copyOf(normalized), tableText, confidence, reviewStatus, List.of(), Instant.now(), "",
            cellsFromGrid(headers, normalized, source), List.of(), source));
      }
      index = cursor - 1;
    }
    return result;
  }

  private List<String> markdownCells(String line) {
    String value = line.trim(); if (value.startsWith("|")) value = value.substring(1); if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
    return Arrays.stream(value.split("\\|", -1)).map(String::trim).toList();
  }
  private List<String> padCells(List<String> values, int columns) {
    List<String> result = new ArrayList<>(values); while (result.size() < columns) result.add("");
    return result.size() > columns ? new ArrayList<>(result.subList(0, columns)) : result;
  }
  private String tableMarkdown(String title, List<String> headers, List<List<String>> rows) {
    StringBuilder result = new StringBuilder(); if (title != null && !title.isBlank()) result.append("**").append(title).append("**\n\n");
    result.append("| ").append(markdownRow(headers)).append(" |\n| ")
        .append(String.join(" | ", java.util.Collections.nCopies(headers.size(), "---"))).append(" |\n");
    rows.forEach(row -> result.append("| ").append(markdownRow(row)).append(" |\n")); return result.toString().trim();
  }
  private String markdownRow(List<String> cells) {
    return cells.stream().map(value -> Objects.toString(value, "").replace("\r", "")
        .replace("\n", "<br>")).collect(java.util.stream.Collectors.joining(" | "));
  }
  private List<TableCell> cellsFromGrid(List<String> headers, List<List<String>> rows, String source) {
    List<TableCell> result = new ArrayList<>();
    for (int column = 0; column < headers.size(); column++) result.add(new TableCell(UUID.randomUUID(), 0, column, 1, 1,
        Objects.toString(headers.get(column), ""), 1d, List.of(), source));
    for (int row = 0; row < rows.size(); row++) for (int column = 0; column < rows.get(row).size(); column++) {
      result.add(new TableCell(UUID.randomUUID(), row + 1, column, 1, 1, Objects.toString(rows.get(row).get(column), ""), 1d, List.of(), source));
    }
    return List.copyOf(result);
  }
  private String concise(Exception error) {
    String message = error.getMessage(); return message == null ? error.getClass().getSimpleName() : message.length() > 180 ? message.substring(0, 180) : message;
  }

  private String extractOffice(Path path, String type) throws IOException {
    if (type == null) return "";
    if (type.endsWith("wordprocessingml.document")) {
      try (var doc = new XWPFDocument(Files.newInputStream(path)); var ex = new XWPFWordExtractor(doc)) { return ex.getText(); }
    }
    if ("application/msword".equals(type)) {
      try (var doc = new HWPFDocument(Files.newInputStream(path)); var ex = new WordExtractor(doc)) { return ex.getText(); }
    }
    if (type.endsWith("presentationml.presentation")) {
      try (var ppt = new XMLSlideShow(Files.newInputStream(path))) { return slideText(ppt.getSlides()); }
    }
    if ("application/vnd.ms-powerpoint".equals(type)) {
      try (var ppt = new HSLFSlideShow(Files.newInputStream(path))) { return slideText(ppt.getSlides()); }
    }
    return "";
  }

  private String slideText(List<? extends Slide<?, ?>> slides) {
    StringBuilder value = new StringBuilder();
    for (var slide : slides) slide.getShapes().forEach(shape -> {
      if (shape instanceof org.apache.poi.sl.usermodel.TextShape<?, ?> text) value.append(text.getText()).append('\n');
    });
    return value.toString();
  }

  private ParsedDocument fromText(UUID id, String status, String text, List<String> warnings,
      List<ParsedTable> tables, Instant when) {
    List<Chunk> chunks = new ArrayList<>(chunk(text, 1200, 160)); int next = chunks.size();
    for (ParsedTable table : tables) {
      for (Chunk tableChunk : tableChunks(table, next)) { chunks.add(tableChunk); next++; }
    }
    return new ParsedDocument(id, status, text, List.copyOf(chunks), chapterHeads(text),
        Math.round(textQuality(text) * 1000d) / 10d, warnings, tables, when);
  }

  private void persistChunks(ParsedDocument document, ProgressListener progress) {
    try {
      jdbc.update("delete from document_chunks where document_id=?", document.documentId());
      String sql = postgres()
          ? "insert into document_chunks(id,document_id,chunk_index,content,source_ref,embedding,embedding_model,content_type,metadata_json,created_at) values(?,?,?,?,?,cast(? as jsonb),?,?,?,?)"
          : "insert into document_chunks(id,document_id,chunk_index,content,source_ref,embedding,embedding_model,content_type,metadata_json,created_at) values(?,?,?,?,?,?,?,?,?,?)";
      int processed = 0;
      for (var chunk : document.chunks()) {
        UUID id = UUID.randomUUID();
        float[] vector = embeddings.embed(chunk.content());
        boolean table = chunk.sourceRef().contains(" · 表格 ");
        jdbc.update(sql, id, document.documentId(), chunk.index(), chunk.content(), chunk.sourceRef(),
            json.writeValueAsString(vector), embeddings.model(), table ? "TABLE" : "TEXT",
            table ? json.writeValueAsString(Map.of("tableAware", true, "headerRepeated", true)) : null,
            java.sql.Timestamp.from(document.parsedAt()));
        try { vectors.save(id, vector); } catch (Exception ignored) { }
        processed++;
        int percent = 84 + (int) Math.round(14d * processed / Math.max(1, document.chunks().size()));
        progress.update("EMBEDDING", percent, processed, document.chunks().size(),
            "正在生成向量 " + processed + " / " + document.chunks().size());
      }
    } catch (Exception e) {
      throw new IllegalStateException("保存分块向量失败", e);
    }
  }

  private void persistTables(ParsedDocument document) {
    try {
      jdbc.update("delete from document_tables where document_id=?", document.documentId());
      for (ParsedTable table : document.tables()) {
        jdbc.update("insert into document_tables(id,document_id,page_no,table_index,title,headers_json,rows_json,markdown,confidence,review_status,warnings_json,structure_html,table_bbox_json,extraction_source,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            table.id(), document.documentId(), table.page(), table.tableIndex(), table.title(),
            json.writeValueAsString(table.headers()), json.writeValueAsString(table.rows()), table.markdown(),
            table.confidence(), table.reviewStatus(), json.writeValueAsString(table.warnings()), table.structureHtml(),
            json.writeValueAsString(table.boundingBox()), table.extractionSource(),
            java.sql.Timestamp.from(table.updatedAt()), java.sql.Timestamp.from(table.updatedAt()));
        saveTableCells(table);
      }
    } catch (Exception error) { throw new IllegalStateException("保存结构化表格失败", error); }
  }

  private void saveTableCells(ParsedTable table) throws Exception {
    for (TableCell cell : table.cells()) {
      jdbc.update("insert into document_table_cells(id,table_id,row_no,column_no,row_span,column_span,cell_text,confidence,bbox_json,source) values(?,?,?,?,?,?,?,?,?,?)",
          cell.id(), table.id(), cell.row(), cell.column(), cell.rowSpan(), cell.columnSpan(), cell.text(), cell.confidence(),
          json.writeValueAsString(cell.boundingBox()), cell.source());
    }
  }

  private void persistPages(UUID documentId, String text, Instant when) {
    try {
      List<PageDraft> drafts = splitPages(text);
      jdbc.update("update document_pages set active=false where document_id=?", documentId);
      for (PageDraft draft : drafts) {
        List<MarkdownPage> existing = jdbc.query("select id,page_no,markdown,review_status,version,warnings_json,confirmed_by,updated_at from document_pages where document_id=? and page_no=?",
            this::mapPage, documentId, draft.page());
        if (existing.isEmpty()) {
          UUID pageId = UUID.randomUUID();
          jdbc.update("insert into document_pages(id,document_id,page_no,markdown,review_status,version,active,warnings_json,confirmed_by,created_at,updated_at) values(?,?,?,?,?,1,true,?,?,?,?)",
              pageId, documentId, draft.page(), draft.markdown(), "AI_EXTRACTED", json.writeValueAsString(List.of()),
              null, java.sql.Timestamp.from(when), java.sql.Timestamp.from(when));
          jdbc.update("insert into document_page_versions(id,document_page_id,version,markdown,review_status,action,actor,created_at) values(?,?,?,?,?,?,?,?)",
              UUID.randomUUID(), pageId, 1, draft.markdown(), "AI_EXTRACTED", "PARSE", "SYSTEM",
              java.sql.Timestamp.from(when));
        } else {
          MarkdownPage old = existing.getFirst();
          if (old.markdown().equals(draft.markdown())) {
            jdbc.update("update document_pages set active=true,updated_at=? where id=?", java.sql.Timestamp.from(when), old.id());
          } else {
            int version = old.version() + 1;
            jdbc.update("update document_pages set markdown=?,review_status='AI_EXTRACTED',version=?,active=true,confirmed_by=null,updated_at=? where id=?",
                draft.markdown(), version, java.sql.Timestamp.from(when), old.id());
            jdbc.update("insert into document_page_versions(id,document_page_id,version,markdown,review_status,action,actor,created_at) values(?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), old.id(), version, draft.markdown(), "AI_EXTRACTED", "REPARSE", "SYSTEM",
                java.sql.Timestamp.from(when));
          }
        }
      }
    } catch (Exception error) { throw new IllegalStateException("保存页面级 Markdown 失败", error); }
  }

  private List<MarkdownPage> loadPages(UUID documentId) {
    return jdbc.query("select id,page_no,markdown,review_status,version,warnings_json,confirmed_by,updated_at from document_pages where document_id=? and active=true order by page_no",
        this::mapPage, documentId);
  }

  private MarkdownPage mapPage(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    try {
      List<String> warnings = rs.getString("warnings_json") == null ? List.of()
          : json.readValue(rs.getString("warnings_json"), new com.fasterxml.jackson.core.type.TypeReference<>() { });
      return new MarkdownPage(UUID.fromString(rs.getString("id")), rs.getInt("page_no"),
          rs.getString("markdown"), rs.getString("review_status"), rs.getInt("version"), warnings,
          rs.getString("confirmed_by"), rs.getTimestamp("updated_at").toInstant());
    } catch (java.sql.SQLException error) { throw error; }
    catch (Exception error) { throw new java.sql.SQLException("页面 Markdown 数据损坏", error); }
  }

  private List<PageDraft> splitPages(String text) {
    String value = Objects.toString(text, "").trim();
    if (value.isBlank()) return List.of();
    Matcher matcher = PAGE_MARKER.matcher(value); List<Integer> pages = new ArrayList<>();
    List<Integer> starts = new ArrayList<>(); List<Integer> bodies = new ArrayList<>();
    while (matcher.find()) { pages.add(Integer.parseInt(matcher.group(1))); starts.add(matcher.start()); bodies.add(matcher.end()); }
    if (pages.isEmpty()) return List.of(new PageDraft(1, ensurePageMarker(1, markdownBody(value))));
    List<PageDraft> result = new ArrayList<>();
    if (starts.getFirst() > 0 && !value.substring(0, starts.getFirst()).isBlank()) {
      result.add(new PageDraft(1, ensurePageMarker(1, markdownBody(value.substring(0, starts.getFirst())))));
    }
    for (int index = 0; index < pages.size(); index++) {
      int end = index + 1 < starts.size() ? starts.get(index + 1) : value.length();
      String body = value.substring(bodies.get(index), end).trim();
      result.add(new PageDraft(pages.get(index), ensurePageMarker(pages.get(index), markdownBody(body))));
    }
    return result;
  }

  private String canonicalMarkdown(String text) {
    return splitPages(text).stream().map(PageDraft::markdown)
        .collect(java.util.stream.Collectors.joining("\n\n"));
  }

  private String ensurePageMarker(int page, String markdown) {
    String body = Objects.toString(markdown, "").trim()
        .replaceFirst("^\\[第\\d+页]\\s*", "").trim();
    return "[第" + page + "页]\n\n" + body;
  }

  /** Converts conservative, layout-free extraction into readable Markdown without inventing content. */
  private String markdownBody(String content) {
    String[] lines = Objects.toString(content, "").replace("\r", "").split("\n", -1);
    StringBuilder result = new StringBuilder(); boolean inTable = false;
    for (String raw : lines) {
      String line = raw.strip();
      if (line.isBlank()) { result.append('\n'); inTable = false; continue; }
      if (line.contains("|") || line.matches("\\s*\\|?\\s*:?-{2,}.*\\|.*")) {
        result.append(line).append('\n'); inTable = true; continue;
      }
      if (inTable || line.startsWith("#") || line.startsWith("- ") || line.startsWith("* ")) {
        result.append(line).append('\n'); continue;
      }
      String heading = markdownHeading(line);
      result.append(heading).append('\n');
    }
    return result.toString().replaceAll("\\n{3,}", "\n\n").trim();
  }

  private String markdownHeading(String line) {
    if (line.matches("^(说\\s*明|目\\s*录|国家职业技能标准|职业技能标准)$")) return "# " + line.replaceAll("\\s+", " ");
    if (line.length() <= 60 && !line.matches(".*[。；！？]$")) {
      Matcher numeric = Pattern.compile("^(\\d+(?:[.．]\\d+){0,4})[.．、]?\\s*(.{2,})$").matcher(line);
      if (numeric.matches()) {
        int depth = Math.min(4, 1 + (int) numeric.group(1).chars().filter(value -> value == '.' || value == '．').count());
        return "#".repeat(depth) + " " + numeric.group(1).replace('．', '.') + " " + numeric.group(2).trim();
      }
      if (line.matches("^[一二三四五六七八九十百]+、.{2,}$")) return "## " + line;
    }
    if (line.startsWith("——") || line.startsWith("— ")) return "- " + line.replaceFirst("^—+\\s*", "");
    return line;
  }

  private List<ParsedTable> loadTables(UUID documentId) {
    return jdbc.query("select id,page_no,table_index,title,headers_json,rows_json,markdown,confidence,review_status,warnings_json,structure_html,table_bbox_json,extraction_source,updated_at from document_tables where document_id=? order by page_no,table_index",
        (rs, row) -> {
          try {
            List<String> headers = json.readValue(rs.getString("headers_json"), new com.fasterxml.jackson.core.type.TypeReference<>() { });
            List<List<String>> rows = json.readValue(rs.getString("rows_json"), new com.fasterxml.jackson.core.type.TypeReference<>() { });
            List<String> tableWarnings = rs.getString("warnings_json") == null ? List.of() : json.readValue(rs.getString("warnings_json"), new com.fasterxml.jackson.core.type.TypeReference<>() { });
            return new ParsedTable(UUID.fromString(rs.getString("id")), rs.getInt("page_no"), rs.getInt("table_index"),
                Objects.toString(rs.getString("title"), ""), headers, rows, rs.getString("markdown"),
                rs.getDouble("confidence"), rs.getString("review_status"), tableWarnings, rs.getTimestamp("updated_at").toInstant(),
                Objects.toString(rs.getString("structure_html"), ""), loadTableCells(UUID.fromString(rs.getString("id"))),
                rs.getString("table_bbox_json") == null ? List.of() : json.readValue(rs.getString("table_bbox_json"), new com.fasterxml.jackson.core.type.TypeReference<>() { }),
                Objects.toString(rs.getString("extraction_source"), "LEGACY"));
          } catch (Exception error) { throw new java.sql.SQLException("表格结构数据损坏", error); }
        }, documentId);
  }

  private List<TableCell> loadTableCells(UUID tableId) {
    return jdbc.query("select id,row_no,column_no,row_span,column_span,cell_text,confidence,bbox_json,source from document_table_cells where table_id=? order by row_no,column_no", (rs, row) -> {
      try {
        List<Integer> bbox = rs.getString("bbox_json") == null ? List.of() : json.readValue(rs.getString("bbox_json"), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        return new TableCell(UUID.fromString(rs.getString("id")), rs.getInt("row_no"), rs.getInt("column_no"),
            rs.getInt("row_span"), rs.getInt("column_span"), rs.getString("cell_text"), rs.getDouble("confidence"), bbox,
            Objects.toString(rs.getString("source"), "LEGACY"));
      } catch (Exception error) { throw new java.sql.SQLException("表格单元格结构数据损坏", error); }
    }, tableId);
  }

  private List<Chunk> tableChunks(ParsedTable table, int startIndex) {
    List<Chunk> result = new ArrayList<>(); List<List<String>> batch = new ArrayList<>(); int size = 0, index = startIndex;
    for (List<String> row : table.rows()) {
      int rowSize = row.stream().mapToInt(String::length).sum() + row.size() * 3;
      if (!batch.isEmpty() && (size + rowSize > 1050 || batch.size() >= 12)) {
        result.add(new Chunk(index++, tableChunkText(table, batch), "第" + table.page() + "页 · 表格 " + (table.tableIndex() + 1)));
        batch = new ArrayList<>(); size = 0;
      }
      batch.add(row); size += rowSize;
    }
    if (!batch.isEmpty()) result.add(new Chunk(index, tableChunkText(table, batch), "第" + table.page() + "页 · 表格 " + (table.tableIndex() + 1)));
    return result;
  }
  private String tableChunkText(ParsedTable table, List<List<String>> rows) {
    String prefix = table.title().isBlank() ? "" : table.title() + "\n";
    return prefix + tableMarkdown("", table.headers(), rows);
  }

  private List<Chunk> chunk(String text, int size, int overlap) {
    List<Chunk> result = new ArrayList<>();
    String normalized = text.replace("\r", "").replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    for (int start = 0, index = 0; start < normalized.length(); index++) {
      int end = Math.min(normalized.length(), start + size);
      if (end < normalized.length()) end = semanticBoundary(normalized, start, end, size);
      String part = normalized.substring(start, end).trim();
      if (!part.isBlank()) result.add(new Chunk(index, part, sourceRef(normalized, start, end)));
      if (end >= normalized.length()) break;
      start = Math.max(end - overlap, start + 1);
    }
    return result;
  }

  private int semanticBoundary(String text, int start, int end, int size) {
    int minimum = start + (int) (size * 0.62);
    for (String separator : List.of("\n\n", "\n", "。", "；")) {
      int found = text.lastIndexOf(separator, end);
      if (found >= minimum) return found + separator.length();
    }
    return end;
  }

  private String sourceRef(String text, int start, int end) {
    Matcher matcher = PAGE_MARKER.matcher(text.substring(0, Math.min(start + 1, text.length())));
    String page = "";
    while (matcher.find()) page = "第" + matcher.group(1) + "页 · ";
    return page + "文本偏移 " + start + "-" + end;
  }

  private List<String> chapterHeads(String text) {
    return Arrays.stream(text.split("\\R")).map(String::trim)
        .filter(s -> s.matches("^(第[一二三四五六七八九十百0-9]+[章节部分]|[一二三四五六七八九十]+、.{2,80}|[0-9]+(?:\\.[0-9]+)*[.、 ]?[^。]{2,80})$"))
        .distinct().limit(200).toList();
  }

  private String cleanExtractedText(String value) {
    return value == null ? "" : value.replace('\u0000', ' ').replaceAll("[ \\t]+", " ")
        .replaceAll("(?m)^\\s*\\d+\\s*$", "").replaceAll("\\n{3,}", "\n\n").trim();
  }

  private boolean goodText(String value) {
    String compact = value == null ? "" : value.replaceAll("\\s", "");
    return compact.length() >= 80 && textQuality(compact) >= 0.72 && !compact.contains("[图片文档：");
  }

  private double textQuality(String value) {
    if (value == null || value.isBlank()) return 0;
    int visible = 0, readable = 0, unknown = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isWhitespace(c)) continue;
      visible++;
      if (Character.isLetterOrDigit(c) || "，。；：！？、（）()《》【】[]-—_./%|<>〔〕?".indexOf(c) >= 0) readable++;
      if (c == '�' || c == '?' || c == '□') unknown++;
    }
    return visible == 0 ? 0 : Math.max(0, (readable - unknown * 2d) / visible);
  }

  private String normalizeImageMime(String type) {
    return SetLike.contains(type) ? type : "image/png";
  }

  private boolean postgres() {
    try (var connection = jdbc.getDataSource().getConnection()) {
      return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");
    } catch (Exception ignored) { return false; }
  }

  private static final class SetLike {
    private static boolean contains(String type) {
      return List.of("image/jpeg", "image/png", "image/gif", "image/webp").contains(type);
    }
  }

  private record Extraction(String text, int unresolvedPages, List<String> warnings, List<ParsedTable> tables) { }
  private record VisionPageResult(String markdown, List<ParsedTable> tables, boolean needsReview) {
    private static final VisionPageResult EMPTY = new VisionPageResult("", List.of(), false);
    private static final VisionPageResult FAILED = new VisionPageResult("", List.of(), true);
  }
  private record PageDraft(int page, String markdown) { }
  private record Stored(String text, String status, double quality, String warnings, java.sql.Timestamp updatedAt) { }
  public record Chunk(int index, String content, String sourceRef) { }
  public record EditableChunk(UUID id, int index, String content, String sourceRef, String embeddingModel,
      String contentType, String metadataJson, Instant createdAt) { }
  public record ParsedTable(UUID id, int page, int tableIndex, String title, List<String> headers,
      List<List<String>> rows, String markdown, double confidence, String reviewStatus,
      List<String> warnings, Instant updatedAt, String structureHtml, List<TableCell> cells,
      List<Integer> boundingBox, String extractionSource) { }
  public record TableCell(UUID id, int row, int column, int rowSpan, int columnSpan, String text,
      double confidence, List<Integer> boundingBox, String source) { }
  public record MarkdownPage(UUID id, int page, String markdown, String reviewStatus, int version,
      List<String> warnings, String confirmedBy, Instant updatedAt) { }
  public record PageBlock(String type, String text, int level, UUID tableId, Integer tableIndex) { }
  public record PageContent(int page, List<PageBlock> blocks, String reviewStatus, int version, Instant updatedAt) { }
  public record PageVersion(int version, String markdown, String reviewStatus, String action,
      String actor, Instant createdAt) { }
  public record PageUpdateResult(MarkdownPage page, boolean vectorsRebuilt, int chunkCount) { }
  public record ParsedDocument(UUID documentId, String status, String fullText, List<Chunk> chunks,
      List<String> chapters, double textQuality, List<String> warnings, List<ParsedTable> tables, Instant parsedAt) { }
  @FunctionalInterface public interface ProgressListener {
    void update(String stage, int progress, int processed, int total, String message);
  }
}
