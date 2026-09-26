package com.tikuzhushou.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.ai.DeepSeekService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VisionOcrService {
  private static final Logger log = LoggerFactory.getLogger(VisionOcrService.class);
  private static final String TABLE_REPAIR_MARKER = "[[OCR_TABLE_REBUILD]]";
  private final DeepSeekService ai;
  private final boolean enabled;
  private final ObjectMapper json;

  public VisionOcrService(DeepSeekService ai, ObjectMapper json,
      @Value("${app.ocr.enabled:false}") boolean enabled) {
    this.ai = ai;
    this.json = json;
    this.enabled = enabled;
  }

  public boolean enabled() { return enabled; }

  public String recognize(byte[] bytes, String mime) { return recognizePage(1, List.of(bytes), mime); }

  public String recognizePage(int page, List<byte[]> orderedCrops, String mime) {
    if (!enabled) throw new IllegalStateException("OCR 未启用");
    String prompt = """
        你是高精度中文文档 OCR 引擎。下面图片按从上到下顺序组成同一页，分块之间可能有少量重叠。
        请逐字转写，不概括、不解释、不纠错、不补写图片中不存在的内容，并遵守：
        1. 删除重叠区域造成的重复文字；保留标题编号、条款编号、标点、数字、单位和等级名称；
        2. 表格使用 Markdown 表格表示，合并单元格内容放在对应行；勾选框和特殊符号按可见内容保留；
        3. 无法辨认的单个字符写作〔?〕，不要猜测；页眉页脚重复内容只保留一次；
        4. 第一行必须是“[第%d页]”，随后仅输出本页正文纯文本/Markdown 表格。
        """.formatted(page);
    String value = ai.analyseImages(prompt, orderedCrops, mime).trim();
    value = value.replaceFirst("(?s)^```(?:markdown|text)?\\s*", "")
        .replaceFirst("(?s)\\s*```$", "");
    if (!value.startsWith("[第")) value = "[第" + page + "页]\n" + value;
    return value;
  }

  /**
   * Produces the same kind of semantic, reading-first reconstruction as the provider's web client.
   * Physical merged cells may be expanded or labelled as continuations because the output is meant
   * for comfortable reading rather than spreadsheet round-tripping.
   */
  public String recognizeReadingPage(int page, List<byte[]> pageAndCrops, String mime, String textLayer,
      String previousPageContext) {
    if (!enabled) throw new IllegalStateException("OCR 未启用");
    String safeTextLayer = limitHead(textLayer, 12_000, "（无可用文字层）");
    String safePrevious = limitTail(previousPageContext, 6_000, "（这是文档第一页或没有可用上文）");
    String activeHeading = lastActiveHeading(safePrevious);
    String basePrompt = """
        你是中文文档阅读版重建引擎。目标是生成与官方网页文件解析相同风格的、清晰易读的 Markdown，
        而不是机械复制物理单元格。第一张图片是第 %d 页全页，其后是同页从上到下的高清分块。

        只输出本页 Markdown，不要代码块、解释、前言或总结，并严格遵守：
        1. 第一行必须是“[第%d页]”。完整保留本页事实、数字、单位、条款编号和可见文字，不概括、不扩写事实。
        2. 根据内容生成清晰的 #/##/### 标题层级和列表；只整理格式，不改变原意。
        3. 所有可见表格必须输出为完整 GFM Markdown 表格，必须有表头和分隔行；单元格换行使用 <br>。
        4. 阅读舒适优先：跨行合并内容在第一行写完整，后续行留空；若留空会产生歧义，可写“（同上）”。
        5. 若本页是上一页表格的续页，结合“上一页上下文”补全被分页切断的职业功能、表名和表头，名称后可加“（续）”。
        6. 不输出 rowSpan、坐标、JSON 或 HTML table；不要使用图片中不存在的专业结论纠正原文。
        7. PDF 文字层字符通常较准但阅读顺序可能错误；以图片确定行列，以文字层校对汉字、数字和标点。

        上一页上下文开始：
        %s
        上一页上下文结束。
        上一页结束时最后一个有效章节标题：%s
        若本页开头是续表，只能承接这个标题，不得改成更早的章节。

        本页 PDF 文字层开始：
        %s
        本页 PDF 文字层结束。
        """.formatted(page, page, safePrevious, activeHeading.isBlank() ? "（无）" : activeHeading, safeTextLayer);
    Exception last = null;
    String partial = "", failure = "表格候选页没有返回 Markdown 表格";
    for (int attempt = 1; attempt <= 2; attempt++) {
      String prompt = attempt == 1 ? basePrompt : basePrompt
          + "\n上一次输出没有形成完整 Markdown 表格。请重新核对网格，并确保表头、分隔行和所有数据行完整。";
      try {
        // A high-reasoning response can occasionally consume its output budget before emitting
        // content.  Retry without thinking so one slow empty response never doubles the delay.
        String value = stripMarkdownFence(ai.analyseImagesReading(prompt, pageAndCrops, mime, attempt == 1));
        if (!value.startsWith("[第")) value = "[第" + page + "页]\n\n" + value;
        value = normalizeContinuationHeading(value, safePrevious, activeHeading);
        if (!containsMarkdownTable(value)) {
          if (!value.isBlank()) partial = value;
          throw new IllegalArgumentException("表格候选页没有返回 Markdown 表格");
        }
        return value.trim();
      } catch (Exception error) {
        last = error; failure = concise(error);
        log.warn("reading OCR page {} attempt {} failed: {}", page, attempt,
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
      }
    }
    if (!partial.isBlank()) {
      try {
        return repairReadingMarkers(page, pageAndCrops, mime, partial, failure, safeTextLayer, safePrevious, activeHeading);
      } catch (Exception repairError) {
        last = repairError;
        log.warn("reading OCR page {} marker repair failed: {}", page,
            repairError.getMessage() == null ? repairError.getClass().getSimpleName() : repairError.getMessage());
      }
    }
    throw new IllegalStateException("TABLE_READING_INVALID：模型连续两次未返回完整阅读版表格", last);
  }

  /**
   * Table-only reconstruction used by the fast document path.  The first image is a low-resolution
   * page overview and the remaining images are table regions, so the model does not spend output
   * tokens rebuilding page prose or Markdown syntax.
   */
  public TableRegionResult recognizeTableRegions(int page, List<byte[]> overviewAndRegions, String mime,
      String textLayer, String previousTableContext) {
    if (!enabled) throw new IllegalStateException("OCR 未启用");
    String safeText = limitHead(textLayer, 6_000, "（无可用文字层）");
    String safePrevious = limitTail(previousTableContext, 3_000, "（无上一页表格）");
    String basePrompt = """
        你是中文职业标准表格识别引擎。第一张图是第 %d 页低清版式定位图；其后是本页表格的高清区域图。
        只输出合法 JSON，不得输出 Markdown、解释或代码块：
        {"body":"仅保留表格外的标题、页眉、页脚或正文；没有则为空", "warnings":[], "tables":[
          {"title":"表名，没有则为空","confidence":0.0,"warnings":[],"cells":[
            {"row":0,"column":0,"rowSpan":1,"columnSpan":1,"text":"单元格原文","confidence":0.0}
          ]}
        ]}
        规则：
        1. 只识别高清区域中可见的表格；不得把表格内容重复写入 body。
        2. cells 必须逐格给出真实 row、column、rowSpan、columnSpan；合并格只出现一次，单元格覆盖不得重叠。confidence 仅在不确定时给出，省略时系统按保守默认值处理。
        3. 所有可见文字、数字、单位、等级和条款编号必须原样保留；无法辨认写〔?〕，不能猜测。
        4. 表格跨页时，参考上一页表格上下文补全本页重复表头；仅在图片支持时才补全。
        5. 必须返回至少一张表格，且每张表格至少两列。不得输出整页 Markdown 表格。
        上一页表格上下文：
        %s
        当前页文字层（仅用于校对字符，不得照搬错误列顺序）：
        %s
        """.formatted(page, safePrevious, safeText);
    Exception last = null;
    for (int attempt = 1; attempt <= 2; attempt++) {
      String prompt = attempt == 1 ? basePrompt : basePrompt
          + "\n上一次结构校验失败。请只重新核对网格、合并单元格和列顺序，返回完整 JSON。";
      try {
        return parseTableRegionResult(page, ai.analyseImagesStructured(prompt, overviewAndRegions, mime, attempt == 1));
      } catch (Exception error) {
        last = error;
        log.warn("table-region OCR page {} attempt {} failed: {}", page, attempt,
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
      }
    }
    throw new IllegalStateException("TABLE_REGION_INVALID：模型连续两次未返回有效单元格结构", last);
  }

  private TableRegionResult parseTableRegionResult(int page, String raw) throws Exception {
    JsonNode root = json.readTree(stripFence(raw));
    if (!root.isObject()) throw new IllegalArgumentException("表格区域响应不是 JSON 对象");
    JsonNode nodes = root.path("tables");
    if (!nodes.isArray() || nodes.isEmpty()) throw new IllegalArgumentException("表格区域响应没有 tables");
    List<StructuredTable> tables = new ArrayList<>(); int index = 0;
    for (JsonNode node : nodes) {
      List<Cell> cells = parseCells(node.path("cells"));
      if (cells.isEmpty()) cells = legacyCells(node);
      validateCells(cells, index + 1);
      Grid grid = grid(cells);
      if (grid.rows().size() < 2) throw new IllegalArgumentException("第 " + (index + 1) + " 个表格没有数据行");
      List<String> headers = grid.rows().getFirst();
      List<List<String>> rows = grid.rows().subList(1, grid.rows().size());
      tables.add(new StructuredTable(Objects.toString(node.path("title").asText(""), ""), List.copyOf(headers),
          List.copyOf(rows), List.copyOf(cells), clamp(node.path("confidence").asDouble(.5)),
          List.copyOf(strings(node.path("warnings")))));
      index++;
    }
    String body = root.path("body").asText("").trim();
    if (!body.startsWith("[第")) body = "[第" + page + "页]" + (body.isBlank() ? "" : "\n" + body);
    return new TableRegionResult(body,
        List.copyOf(strings(root.path("warnings"))), List.copyOf(tables));
  }

  /**
   * Repairs a named missing table region after both full-page attempts returned usable but incomplete
   * Markdown.  The model returns a tiny JSON patch, so it cannot replace already validated page text.
   */
  private String repairReadingMarkers(int page, List<byte[]> pageAndCrops, String mime, String partial,
      String failure, String textLayer, String previousContext, String activeHeading) throws Exception {
    String marked = limitHead(partial, 16_000, "[第" + page + "页]") + "\n\n" + TABLE_REPAIR_MARKER;
    String prompt = """
        你负责修复中文职业标准 OCR 的一个明确缺口。请只重建标记 %s 对应的 Markdown 表格，
        不得改写、删除或补造现有正文。只输出合法 JSON：
        {"patches":[{"marker":"%s","replacement":"| 表头 | 表头 |\\n| --- | --- |\\n| 单元格 | 单元格 |"}]}
        replacement 必须是完整 GFM Markdown 表格，包含表头、分隔行和所有当前页可见数据行。
        以图片为准，文字层仅用于校对汉字、数字和标点；无法辨认处写〔?〕，不能猜测。
        当前结构校验失败原因：%s
        上一页有效上下文：%s
        当前页文字层：%s
        当前页已有 Markdown（不可改写）：
        %s
        """.formatted(TABLE_REPAIR_MARKER, TABLE_REPAIR_MARKER, failure,
        limitTail(previousContext, 3_000, "（无）"), limitHead(textLayer, 6_000, "（无）"), marked);
    String raw = ai.analyseImagesReadingPatch(prompt, pageAndCrops, mime);
    JsonNode root = json.readTree(stripFence(raw));
    JsonNode patches = root.path("patches");
    if (!patches.isArray()) throw new IllegalArgumentException("标记点修复响应缺少 patches 数组");
    String replacement = "";
    for (JsonNode patch : patches) {
      if (TABLE_REPAIR_MARKER.equals(patch.path("marker").asText())) {
        replacement = stripMarkdownFence(patch.path("replacement").asText("")); break;
      }
    }
    if (!containsMarkdownTable(replacement)) throw new IllegalArgumentException("标记点修复未返回完整 Markdown 表格");
    String repaired = marked.replace(TABLE_REPAIR_MARKER, replacement).trim();
    repaired = normalizeContinuationHeading(repaired, previousContext, activeHeading);
    if (!containsMarkdownTable(repaired)) throw new IllegalArgumentException("标记点修复后的页面结构仍不完整");
    log.info("reading OCR page {} repaired missing table marker", page);
    return repaired;
  }

  private String limitHead(String value, int maximum, String fallback) {
    String safe = Objects.toString(value, "").trim();
    if (safe.isBlank()) return fallback;
    return safe.length() <= maximum ? safe : safe.substring(0, maximum);
  }

  private String limitTail(String value, int maximum, String fallback) {
    String safe = Objects.toString(value, "").trim();
    if (safe.isBlank()) return fallback;
    return safe.length() <= maximum ? safe : safe.substring(safe.length() - maximum);
  }

  private boolean containsMarkdownTable(String value) {
    String[] lines = Objects.toString(value, "").replace("\r", "").split("\n", -1);
    for (int index = 0; index + 1 < lines.length; index++) {
      if (lines[index].contains("|") && lines[index + 1].matches("\\s*\\|?\\s*:?-{2,}.*\\|.*")) return true;
    }
    return false;
  }

  private String stripMarkdownFence(String value) {
    return Objects.toString(value, "").trim()
        .replaceFirst("(?s)^```(?:markdown|text)?\\s*", "")
        .replaceFirst("(?s)\\s*```$", "");
  }

  private String concise(Exception error) {
    String value = error == null || error.getMessage() == null ? "模型响应不完整" : error.getMessage();
    return value.replaceAll("\\s+", " ").substring(0, Math.min(value.length(), 240));
  }

  private String lastActiveHeading(String context) {
    String result = "";
    for (String line : Objects.toString(context, "").replace("\r", "").split("\n")) {
      String trimmed = line.trim();
      if (trimmed.matches("^#{1,6}\\s+\\d+(?:\\.\\d+)+\\s+.+")) result = trimmed;
    }
    return result;
  }

  /** Corrects attractive but factually wrong continuation labels using the prior page's last section. */
  private String normalizeContinuationHeading(String markdown, String previousContext, String activeHeading) {
    if (activeHeading.isBlank()) return markdown;
    String[] lines = markdown.replace("\r", "").split("\n", -1);
    int firstTable = -1, firstHeading = -1;
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index].trim();
      if (firstHeading < 0 && line.matches("^#{1,6}\\s+.+")) firstHeading = index;
      if (firstTable < 0 && index + 1 < lines.length && line.contains("|")
          && lines[index + 1].matches("\\s*\\|?\\s*:?-{2,}.*\\|.*")) firstTable = index;
    }
    if (firstTable < 0) return markdown;
    String base = activeHeading.replaceFirst("\\s*[（(]续[）)]\\s*$", "");
    int activeAt = previousContext.lastIndexOf(activeHeading);
    String afterHeading = activeAt < 0 ? "" : previousContext.substring(activeAt + activeHeading.length())
        .replaceAll("\\[第\\d+页]", "").trim();
    String carry = afterHeading.isBlank() ? base : base + "（续）";
    if (firstHeading >= 0 && firstHeading < firstTable && lines[firstHeading].matches(".*[（(]续[）)].*")) {
      lines[firstHeading] = carry;
      return String.join("\n", lines);
    }
    if (firstHeading < 0 || firstTable < firstHeading) {
      List<String> normalized = new ArrayList<>(List.of(lines));
      normalized.add(firstTable, carry);
      normalized.add(firstTable + 1, "");
      return String.join("\n", normalized);
    }
    return markdown;
  }

  /**
   * Full-page table extraction. It deliberately keeps this separate from ordinary band OCR because cutting a
   * table into horizontal images destroys row/column relationships.
   */
  public StructuredPage recognizeStructuredPage(int page, byte[] fullPage, String mime) {
    return recognizeStructuredPage(page, fullPage, mime, "");
  }

  public StructuredPage recognizeStructuredPage(int page, byte[] fullPage, String mime, String textLayer) {
    return recognizeStructuredPage(page, List.of(fullPage), mime, textLayer);
  }

  /** First image is the whole page; following images are high-resolution reading-order crops. */
  public StructuredPage recognizeStructuredPage(int page, List<byte[]> pageAndCrops, String mime, String textLayer) {
    if (!enabled) throw new IllegalStateException("OCR 未启用");
    String safeTextLayer = Objects.toString(textLayer, "").trim();
    if (safeTextLayer.length() > 12_000) safeTextLayer = safeTextLayer.substring(0, 12_000);
    String basePrompt = """
        你是高精度中文 PDF 版面和表格识别引擎。请检查整页图片，逐字转写正文，并恢复表格真实行列关系。
        必须只输出一个合法 JSON 对象，不得输出代码块或说明文字，结构如下：
        {"page":%d,"hasTable":true,"markdown":"正文与[TABLE_0]占位符，第一行必须是[第%d页]",
         "confidence":0.0,"warnings":[],"tables":[
          {"title":"表名，没有则为空","confidence":0.0,"warnings":[],"cells":[
           {"row":0,"column":0,"rowSpan":1,"columnSpan":1,"text":"单元格原文","confidence":0.0}
          ]}
         ]}
        规则：
        1. 不概括、不改写、不补造；无法辨认写作〔?〕；所有数字、单位、等级和条款编号必须保留。
        2. cells 必须逐格返回真实 row/column；合并格只返回一次，并用 rowSpan/columnSpan 表达，不得用空白格伪造合并。
        3. 不得让单元格覆盖范围重叠；跨行单元格不得把相邻列拼接。confidence 必须逐格给出。
        4. markdown 只包含表格前后的正文，并在表格原位置写 [TABLE_0]、[TABLE_1]；不要再输出 Markdown 表格。
        5. 此接口只在本地网格/文字规则已判定为表格候选页后调用，所以必须令 hasTable=true，并重建所有可见表格；不得因为表格跨页、缺少表头或只显示一部分而返回 false。
        6. 第一张图是全页版式，其后是同一页按从上到下顺序裁出的高清区域，可能少量重叠；结构以全页为准，文字优先参考高清裁图并去重。
        7. 下面附有 PDF 原始文字层。它的行列顺序可能错误，但字符通常比视觉 OCR 准确；请依据图片决定结构，并优先用文字层校对汉字、数字和标点。不得照搬错误的列顺序。
        8. 页首和页尾的无表头网格通常是相邻页表格的续表，也必须作为 table 返回；一页有多张分离表格时按从上到下编号，不能漏掉任何一张。
        PDF文字层开始：
        %s
        PDF文字层结束。
        """.formatted(page, page, safeTextLayer.isBlank() ? "（无可用文字层）" : safeTextLayer);
    Exception last = null;
    for (int attempt = 1; attempt <= 2; attempt++) {
      String prompt = attempt == 1 ? basePrompt : basePrompt + "\n上一次结果未通过结构校验。请重新核对所有网格线、跨页片段和多张表格，确保 JSON 完整且单元格不重叠。";
      try {
        String raw = stripFence(ai.analyseImagesJson(prompt, pageAndCrops, mime));
        StructuredPage parsed = parseStructured(page, raw);
        if (!parsed.hasTable() || parsed.tables().isEmpty())
          throw new IllegalArgumentException("本地预检已确认疑似表格，但模型返回无表格");
        return parsed;
      } catch (Exception error) {
        last = error;
        log.warn("structured OCR page {} attempt {} failed: {}", page, attempt,
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
      }
    }
    log.error("structured OCR page {} failed after 2 attempts", page);
    throw new IllegalStateException("TABLE_VISION_INVALID：模型连续两次未返回完整表格结构", last);
  }

  private StructuredPage parseStructured(int expectedPage, String raw) throws Exception {
    JsonNode root = json.readTree(raw);
    if (!root.isObject()) throw new IllegalArgumentException("表格响应不是 JSON 对象");
    boolean hasTable = root.path("hasTable").asBoolean(false);
    String markdown = root.path("markdown").asText("").trim();
    if (markdown.isBlank()) throw new IllegalArgumentException("表格响应缺少完整页面 Markdown");
    if (!markdown.startsWith("[第")) markdown = "[第" + expectedPage + "页]\n" + markdown;
    List<String> pageWarnings = strings(root.path("warnings")); List<StructuredTable> tables = new ArrayList<>();
    if (hasTable) {
      JsonNode nodes = root.path("tables");
      if (!nodes.isArray() || nodes.isEmpty()) throw new IllegalArgumentException("hasTable=true 但没有表格数据");
      int index = 0;
      for (JsonNode node : nodes) {
        List<Cell> cells = parseCells(node.path("cells"));
        if (cells.isEmpty()) cells = legacyCells(node);
        validateCells(cells, index + 1);
        Grid grid = grid(cells);
        List<String> headers = grid.rows().getFirst();
        List<List<String>> normalizedRows = grid.rows().size() > 1 ? grid.rows().subList(1, grid.rows().size()) : List.of();
        double confidence = clamp(node.path("confidence").asDouble(root.path("confidence").asDouble(0.5)));
        tables.add(new StructuredTable(Objects.toString(node.path("title").asText(""), ""),
            List.copyOf(headers), List.copyOf(normalizedRows), List.copyOf(cells), confidence, List.copyOf(strings(node.path("warnings")))));
        index++;
      }
    }
    return new StructuredPage(expectedPage, hasTable, markdown, clamp(root.path("confidence").asDouble(0.5)),
        List.copyOf(pageWarnings), List.copyOf(tables));
  }

  private List<String> strings(JsonNode node) {
    if (!node.isArray()) return new ArrayList<>(); List<String> values = new ArrayList<>();
    node.forEach(value -> values.add(value.isNull() ? "" : value.asText("").trim())); return values;
  }
  private List<List<String>> matrix(JsonNode node) {
    if (!node.isArray()) return List.of(); List<List<String>> rows = new ArrayList<>();
    node.forEach(row -> rows.add(strings(row))); return rows;
  }
  private List<Cell> parseCells(JsonNode node) {
    if (!node.isArray()) return List.of(); List<Cell> cells = new ArrayList<>();
    for (JsonNode value : node) cells.add(new Cell(Math.max(0, value.path("row").asInt()),
        Math.max(0, value.path("column").asInt()), Math.max(1, value.path("rowSpan").asInt(1)),
        Math.max(1, value.path("columnSpan").asInt(1)), value.path("text").asText("").trim(),
        clamp(value.path("confidence").asDouble(.5))));
    return cells;
  }
  private List<Cell> legacyCells(JsonNode node) {
    List<String> headers = strings(node.path("headers")); List<List<String>> rows = matrix(node.path("rows"));
    List<Cell> cells = new ArrayList<>();
    for (int c = 0; c < headers.size(); c++) cells.add(new Cell(0, c, 1, 1, headers.get(c), .5));
    for (int r = 0; r < rows.size(); r++) for (int c = 0; c < rows.get(r).size(); c++)
      cells.add(new Cell(r + 1, c, 1, 1, rows.get(r).get(c), .5));
    return cells;
  }
  private void validateCells(List<Cell> cells, int table) {
    if (cells.isEmpty()) throw new IllegalArgumentException("第 " + table + " 个表格没有单元格");
    java.util.Set<String> occupied = new java.util.HashSet<>(); int maxColumn = 0;
    for (Cell cell : cells) {
      maxColumn = Math.max(maxColumn, cell.column() + cell.columnSpan());
      for (int r = cell.row(); r < cell.row() + cell.rowSpan(); r++) for (int c = cell.column(); c < cell.column() + cell.columnSpan(); c++)
        if (!occupied.add(r + ":" + c)) throw new IllegalArgumentException("第 " + table + " 个表格存在重叠单元格");
    }
    if (maxColumn < 2) throw new IllegalArgumentException("第 " + table + " 个表格列数不足");
  }
  private Grid grid(List<Cell> cells) {
    int rowCount = 0, columns = 0;
    for (Cell cell : cells) { rowCount = Math.max(rowCount, cell.row() + cell.rowSpan()); columns = Math.max(columns, cell.column() + cell.columnSpan()); }
    List<List<String>> rows = new ArrayList<>();
    for (int r = 0; r < rowCount; r++) rows.add(new ArrayList<>(java.util.Collections.nCopies(columns, "")));
    for (Cell cell : cells) rows.get(cell.row()).set(cell.column(), cell.text());
    return new Grid(rows.stream().map(List::copyOf).toList());
  }
  private List<String> padded(List<String> values, int size) {
    List<String> result = new ArrayList<>(values);
    while (result.size() < size) result.add("");
    if (result.size() > size) result = new ArrayList<>(result.subList(0, size));
    return result;
  }
  private double clamp(double value) { return Math.max(0, Math.min(1, value)); }
  private String stripFence(String value) {
    String result = Objects.toString(value, "").trim();
    result = result.replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("(?s)\\s*```$", "");
    int first = result.indexOf('{'), last = result.lastIndexOf('}');
    return first >= 0 && last > first ? result.substring(first, last + 1) : result;
  }

  public record StructuredPage(int page, boolean hasTable, String markdown, double confidence,
      List<String> warnings, List<StructuredTable> tables) { }
  public record TableRegionResult(String body, List<String> warnings, List<StructuredTable> tables) { }
  public record StructuredTable(String title, List<String> headers, List<List<String>> rows, List<Cell> cells,
      double confidence, List<String> warnings) { }
  public record Cell(int row, int column, int rowSpan, int columnSpan, String text, double confidence) { }
  private record Grid(List<List<String>> rows) { }
}
