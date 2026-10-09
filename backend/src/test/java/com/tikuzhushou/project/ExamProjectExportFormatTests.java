package com.tikuzhushou.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExamProjectExportFormatTests {
  @Test
  void groupsAndRenumbersWordAndExcelWithMatchingAnswersAndOriginalNumberAudit() throws Exception {
    ExamProjectExportService service = new ExamProjectExportService(null, new ObjectMapper(), null, null, null, null, "");
    Object header = header();
    List<Object> rows = mixedRows();
    List<String> expectedHeadings = List.of("1．（单选题，2分）", "2．（单选题，4分）", "3．（填空题，3分）", "4．（简答题，7分）");
    for (String kind : List.of("paper", "answer")) {
      byte[] bytes = invoke(service, "buildDocx", header, kind, rows);
      validate(service, "validateDocx", bytes, header, kind, rows);
      try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
        List<String> text = document.getParagraphs().stream().map(p -> p.getText()).toList();
        int secondVariant = text.indexOf("第B套");
        assertEquals(expectedHeadings, text.subList(0, secondVariant).stream().filter(value -> value.matches("\\d+．.*")).toList());
        assertEquals(expectedHeadings, text.subList(secondVariant, text.size()).stream().filter(value -> value.matches("\\d+．.*")).toList());
        assertTrue(text.contains("一、单选题（共2题，共6分）"));
        assertTrue(text.contains("二、填空题（共1题，每题3分，共3分）"));
        assertTrue(text.indexOf("原5单选") < text.indexOf("原9单选"));
        assertTrue(text.indexOf("原9单选") < text.indexOf("原2填空"));
        assertTrue(text.indexOf("原2填空") < text.indexOf("原1简答"));
        if (kind.equals("answer")) {
          for (String stem : List.of("原5单选", "原9单选", "原2填空", "原1简答")) {
            int position = text.indexOf(stem);
            int nextHeading = position + 1;
            while (nextHeading < text.size() && !text.get(nextHeading).matches("\\d+．.*")) nextHeading++;
            assertTrue(text.subList(position, nextHeading).contains("答案来自" + stem));
          }
        }
      }
    }
    byte[] approval = invoke(service, "buildDocx", header, "approval", rows);
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(approval))) {
      var table = document.getTables().getFirst();
      assertEquals("原题号", table.getRow(0).getCell(1).getText());
      assertEquals("导出题号", table.getRow(0).getCell(2).getText());
      assertEquals("1", table.getRow(1).getCell(1).getText());
      assertEquals("4", table.getRow(1).getCell(2).getText());
    }
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Class<?> stylesType = Class.forName("com.tikuzhushou.project.ExamProjectExportService$Styles");
      Constructor<?> constructor = stylesType.getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      Object styles = constructor.newInstance(workbook);
      for (String methodName : List.of("writePaper", "writeAnswer", "writeApproval")) {
        Method method = ExamProjectExportService.class.getDeclaredMethod(methodName, XSSFWorkbook.class, stylesType, header.getClass(), List.class);
        method.setAccessible(true);
        method.invoke(service, workbook, styles, header, rows);
      }
      for (String variant : List.of("A", "B")) {
        var paper = workbook.getSheet("试卷-第" + variant + "套");
        var answer = workbook.getSheet("答案-第" + variant + "套");
        List<Integer> paperNumbers = new java.util.ArrayList<>(), answerNumbers = new java.util.ArrayList<>();
        List<String> stems = new java.util.ArrayList<>(), answers = new java.util.ArrayList<>();
        for (var row : paper) if (row.getCell(0) != null && row.getCell(0).getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
          paperNumbers.add((int) row.getCell(0).getNumericCellValue()); stems.add(row.getCell(4).getStringCellValue());
        }
        for (var row : answer) if (row.getCell(0) != null && row.getCell(0).getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
          answerNumbers.add((int) row.getCell(0).getNumericCellValue()); answers.add(row.getCell(4).getStringCellValue());
        }
        assertEquals(List.of(1, 2, 3, 4), paperNumbers);
        assertEquals(paperNumbers, answerNumbers);
        assertEquals(List.of("原5单选", "原9单选", "原2填空", "原1简答"), stems);
        assertEquals(stems.stream().map(stem -> "答案来自" + stem).toList(), answers);
        assertEquals(3, paper.getNumMergedRegions());
      }
      assertEquals("导出题号", workbook.getSheet("审批表").getRow(11).getCell(10).getStringCellValue());
      assertEquals(1, workbook.getSheet("审批表").getRow(12).getCell(1).getNumericCellValue());
      assertEquals(4, workbook.getSheet("审批表").getRow(12).getCell(10).getNumericCellValue());
    }
  }

  @Test
  void groupsPdfUsingTheSameNumberingAndStartsEachVariantOnANewPage() throws Exception {
    Path font = findFont();
    assumeTrue(font != null, "测试环境没有可嵌入的中文字体");
    ExamProjectExportService service = new ExamProjectExportService(null, new ObjectMapper(), null, null, null, null, font.toString());
    for (String kind : List.of("paper", "answer")) {
      byte[] bytes = invoke(service, "buildPdf", header(), kind, mixedRows());
      try (PDDocument document = Loader.loadPDF(bytes)) {
        String text = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
        assertTrue(text.contains("一、单选题（共2题，共6分）"));
        assertTrue(text.contains("二、填空题（共1题，每题3分，共3分）"));
        assertTrue(text.contains("1．（单选题，2分）原5单选"));
        assertTrue(text.contains("2．（单选题，4分）原9单选"));
        assertTrue(text.contains("3．（填空题，3分）原2填空"));
        assertTrue(text.contains("4．（简答题，7分）原1简答"));
        var stripper = new PDFTextStripper();
        stripper.setStartPage(1); stripper.setEndPage(1);
        assertFalse(stripper.getText(document).contains("第B套"));
        assertTrue(document.getNumberOfPages() >= 2);
      }
    }
  }

  private static List<Object> mixedRows() throws Exception {
    List<Object> rows = new java.util.ArrayList<>();
    for (int variant = 1; variant <= 2; variant++) {
      rows.add(groupedRow(variant, 1, "SHORT_ANSWER", "简答题", 7, "原1简答"));
      rows.add(groupedRow(variant, 9, "SINGLE_CHOICE", "单选题", 4, "原9单选"));
      rows.add(groupedRow(variant, 2, "FILL_BLANK", "FILL_BLANK", 3, "原2填空"));
      rows.add(groupedRow(variant, 5, "SINGLE_CHOICE", "单选题", 2, "原5单选"));
    }
    return rows;
  }

  private static Object groupedRow(int variant, int sequence, String questionType, String typeLabel, int points, String stem) throws Exception {
    Class<?> type = Class.forName("com.tikuzhushou.project.ExamProjectExportService$QuestionRow");
    Constructor<?> constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    return constructor.newInstance(variant, variant == 1 ? "A" : "B", sequence, questionType, typeLabel, "MEDIUM", points, "APPROVED",
        Map.of("stem", stem, "options", "", "answer", "答案来自" + stem, "analysis", "解析来自" + stem), 2, "teacher", "已核对", Instant.now());
  }

  @Test
  void separatesTheLongCadLineTypeOptionsReportedInTheExportScreenshot() throws Exception {
    ExamProjectExportService service = new ExamProjectExportService(null, new ObjectMapper(), null, null, null, null, "");
    List<String> contents = List.of(
        "①粗实线（粗线）；②细虚线（细线）；③细点画线（细线）；④细实线（细线）；⑤细实线（细线）",
        "①粗实线（粗线）；②粗虚线（粗线）；③细实线（细线）；④细点画线（细线）；⑤细点画线（细线）",
        "①细实线（细线）；②细虚线（细线）；③粗点画线（粗线）；④细实线（细线）；⑤细点画线（细线）",
        "①粗实线（粗线）；②细实线（细线）；③细点画线（细线）；④细虚线（细线）；⑤细实线（细线）");
    String options = java.util.stream.IntStream.range(0, contents.size())
        .mapToObj(index -> Character.toString('A' + index) + ". " + contents.get(index))
        .collect(java.util.stream.Collectors.joining(" | "));
    Object row = layoutQuestion(1, "A", 1, Map.of("stem", "某零件图中需要配置下列图线用途，按制图国家标准对图线种类与线宽类别的规定，下列配置正确的是：", "options", options));
    byte[] bytes = invoke(service, "buildDocx", header(), "paper", List.of(row));
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
      var paragraphs = document.getParagraphs().stream().filter(p -> p.getText().matches("^[A-D]\\.\\t.*")).toList();
      assertEquals(4, paragraphs.size());
      for (int index = 0; index < paragraphs.size(); index++) {
        assertEquals(Character.toString('A' + index) + ".\t" + contents.get(index), paragraphs.get(index).getText());
        assertEquals(480, paragraphs.get(index).getIndentationLeft());
        assertEquals(360, paragraphs.get(index).getIndentationHanging());
      }
      assertFalse(document.getParagraphs().stream().anyMatch(p -> p.getText().startsWith("选项：")));
      assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().equals("1．（单选题，2分）")));
    }
  }

  @Test
  void putsEachHistoricalAndStructuredWordOptionInItsOwnParagraph() throws Exception {
    ExamProjectExportService service = new ExamProjectExportService(null, new ObjectMapper(), null, null, null, null, "");
    List<Object> formats = List.of(
        "A. 先停机 | B. 核对记录 | C. 检查防护 | D. 报告异常",
        "A. 先停机 B. 核对记录 C. 检查防护 D. 报告异常",
        "A、先停机\r\nB、核对记录\r\nC、检查防护\r\nD、报告异常",
        "Ａ．先停机｜Ｂ．核对记录｜Ｃ．检查防护｜Ｄ．报告异常",
        Map.of("D", "报告异常", "B", "核对记录", "A", "先停机", "C", "检查防护"),
        List.of("先停机", "核对记录", "检查防护", "报告异常"),
        List.of(Map.of("label", "A", "text", "先停机"), Map.of("label", "B", "content", "核对记录"),
            Map.of("key", "C", "value", "检查防护"), Map.of("D", "报告异常")),
        "{\"A\":\"先停机\",\"B\":\"核对记录\",\"C\":\"检查防护\",\"D\":\"报告异常\"}",
        "[\"先停机\",\"核对记录\",\"检查防护\",\"报告异常\"]");
    for (Object options : formats) {
      Object row = layoutQuestion(1, "A", 1, Map.of("stem", "应当先做什么？", "options", options));
      byte[] bytes = invoke(service, "buildDocx", header(), "paper", List.of(row));
      try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
        var optionParagraphs = document.getParagraphs().stream().filter(p -> p.getText().matches("^[A-D]\\.\\t.*")).toList();
        assertEquals(List.of("A.\t先停机", "B.\t核对记录", "C.\t检查防护", "D.\t报告异常"),
            optionParagraphs.stream().map(p -> p.getText()).toList(), options.toString());
        for (var paragraph : optionParagraphs) {
          assertEquals(480, paragraph.getIndentationLeft());
          assertEquals(360, paragraph.getIndentationHanging());
          assertEquals(12, paragraph.getRuns().getFirst().getFontSizeAsDouble());
          assertEquals("宋体", paragraph.getRuns().getFirst().getFontFamily(org.apache.poi.xwpf.usermodel.XWPFRun.FontCharRange.eastAsia));
          assertEquals(1.25, paragraph.getSpacingBetween(), .001);
          assertFalse(paragraph.isKeepNext());
        }
      }
    }
  }

  @Test
  void preservesRealWordBreaksAndKeepsAnswerBlocksSeparateFromThePaper() throws Exception {
    ExamProjectExportService service = new ExamProjectExportService(null, new ObjectMapper(), null, null, null, null, "");
    String longOption = "在确认防护装置、启动联锁和现场人员安全后，再按照操作规程完成检查，记录设备状态，并向负责人报告异常。".repeat(3);
    Map<String, Object> content = new LinkedHashMap<>(Map.of(
        "stem", "根据现场检查记录回答问题。\r\n（1）核对启动条件。\n（2）说明异常处置。",
        "options", "A. " + longOption + "\n补充说明：保留原始记录。 | B. 计算 |x| | C. 继续观察 | D. 最后核对；",
        "answer", "A\n先停止操作，再核实原因。",
        "analysis", "先核对启动条件。\n随后确认防护装置状态。",
        "scoringRubric", "不应重复导出的旧评分说明",
        "scoringItems", List.of(Map.of("criterion", "指出启动条件", "points", 1), Map.of("criterion", "说明异常处置", "points", 1))));
    Object row = layoutQuestion(1, "A", 1, content);
    Object header = header();
    byte[] answer = invoke(service, "buildDocx", header, "answer", List.of(row));
    validate(service, "validateDocx", answer, header, "answer", List.of(row));
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(answer))) {
      var stem = document.getParagraphs().stream().filter(p -> p.getText().startsWith("根据现场")).findFirst().orElseThrow();
      assertEquals("根据现场检查记录回答问题。\n（1）核对启动条件。\n（2）说明异常处置。", stem.getText());
      assertEquals(2, stem.getRuns().getFirst().getCTR().sizeOfBrArray());
      assertTrue(stem.isKeepNext());
      var option = document.getParagraphs().stream().filter(p -> p.getText().startsWith("A.\t")).findFirst().orElseThrow();
      assertTrue(option.getText().contains("\n补充说明：保留原始记录。"));
      assertEquals(1, option.getRuns().getFirst().getCTR().sizeOfBrArray());
      assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().equals("B.\t计算 |x|")));
      assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().equals("D.\t最后核对；")));
      List<String> paragraphs = document.getParagraphs().stream().map(p -> p.getText()).toList();
      assertTrue(paragraphs.contains("先核对启动条件。"));
      assertTrue(paragraphs.contains("随后确认防护装置状态。"));
      assertTrue(paragraphs.contains("指出启动条件（1分）"));
      assertTrue(paragraphs.contains("说明异常处置（1分）"));
      assertFalse(String.join("", paragraphs).contains("旧评分说明"));
      assertFalse(String.join("", paragraphs).contains("审核人"));
    }
    byte[] paper = invoke(service, "buildDocx", header, "paper", List.of(row));
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(paper))) {
      String text = document.getParagraphs().stream().map(p -> p.getText()).reduce("", String::concat);
      assertFalse(text.contains("答案："));
      assertFalse(text.contains("解析："));
      assertFalse(text.contains("评分细则："));
      assertFalse(text.contains("中等"));
    }
  }

  @Test
  void startsEachWordVariantOnANewPageAndSplitsLegacyScoringPoints() throws Exception {
    ExamProjectExportService service = new ExamProjectExportService(null, new ObjectMapper(), null, null, null, null, "");
    Object header = header();
    String longStem = "检查设备防护状态时，应依据现场记录核对启动条件、联锁状态和异常处置流程，不得仅凭外观判断设备安全。".repeat(5);
    List<Object> rows = new java.util.ArrayList<>();
    for (int variant = 1; variant <= 2; variant++) for (int sequence = 1; sequence <= 3; sequence++) {
      rows.add(layoutQuestion(variant, variant == 1 ? "A" : "B", sequence,
          Map.of("stem", longStem, "options", Map.of("A", longStem, "B", "核对记录", "C", "检查防护", "D", "报告异常"),
              "answer", "A", "analysis", "先核对条件。\n再检查状态。", "scoringRubric", "核对条件得1分；检查状态得1分。")));
    }
    for (String kind : List.of("paper", "answer")) {
      byte[] bytes = invoke(service, "buildDocx", header, kind, rows);
      validate(service, "validateDocx", bytes, header, kind, rows);
      try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
        var variants = document.getParagraphs().stream().filter(p -> p.getText().matches("第[AB]套")).toList();
        assertEquals(2, variants.size());
        assertFalse(variants.getFirst().isPageBreak());
        assertTrue(variants.getLast().isPageBreak());
        assertTrue(variants.stream().allMatch(p -> p.isKeepNext()));
        assertTrue(document.getParagraphs().stream().filter(p -> p.getText().matches("\\d+．.*")).allMatch(p -> p.isKeepNext()));
        if (kind.equals("answer")) {
          assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().equals("核对条件得1分")));
          assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().equals("检查状态得1分。")));
        }
      }
      String samples = System.getProperty("export.layout.samples");
      if (samples != null && !samples.isBlank()) {
        Path directory = Files.createDirectories(Path.of(samples));
        Files.write(directory.resolve(kind + ".docx"), bytes);
      }
    }
  }

  private static Object layoutQuestion(int variant, String label, int sequence, Map<String, Object> content) throws Exception {
    Class<?> type = Class.forName("com.tikuzhushou.project.ExamProjectExportService$QuestionRow");
    Constructor<?> constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    return constructor.newInstance(variant, label, sequence, "SINGLE_CHOICE", "单选题", "MEDIUM", 2, "APPROVED",
        content, 2, "teacher", "已核对", Instant.parse("2026-09-23T10:00:00Z"));
  }

  @Test
  void keepsVisualStimulusInWordAndPdfPaper() throws Exception {
    Path font = findFont();
    assumeTrue(font != null, "测试环境没有可嵌入的中文字体");
    ExamProjectExportService service = new ExamProjectExportService(null, new ObjectMapper(), null, null, null, null, font.toString());
    KnowledgeVisualService visuals = mock(KnowledgeVisualService.class);
    Field field = ExamProjectExportService.class.getDeclaredField("visuals");
    field.setAccessible(true);
    field.set(service, visuals);
    UUID document = UUID.randomUUID();
    BufferedImage image = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    ImageIO.write(image, "png", png);
    when(visuals.page(any(UUID.class), eq(document), eq(1), eq(0), eq(0), eq(100), eq(100)))
        .thenReturn(png.toByteArray());
    Object row = visualQuestion(document);
    Object header = header();

    byte[] docx = invoke(service, "buildDocx", header, "paper", List.of(row));
    try (XWPFDocument documentFile = new XWPFDocument(new ByteArrayInputStream(docx))) {
      assertTrue(documentFile.getAllPictures().size() >= 1);
    }
    byte[] pdf = invoke(service, "buildPdf", header, "paper", List.of(row));
    try (PDDocument documentFile = Loader.loadPDF(pdf)) {
      boolean found = false;
      for (var page : documentFile.getPages()) for (var name : page.getResources().getXObjectNames())
        if (page.getResources().getXObject(name) instanceof PDImageXObject) found = true;
      assertTrue(found);
    }
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Class<?> stylesType = Class.forName("com.tikuzhushou.project.ExamProjectExportService$Styles");
      Constructor<?> stylesConstructor = stylesType.getDeclaredConstructors()[0];
      stylesConstructor.setAccessible(true);
      Object styles = stylesConstructor.newInstance(workbook);
      Method writePaper = ExamProjectExportService.class.getDeclaredMethod("writePaper", XSSFWorkbook.class,
          stylesType, header.getClass(), List.class);
      writePaper.setAccessible(true);
      writePaper.invoke(service, workbook, styles, header, List.of(row));
      assertTrue(workbook.getAllPictures().size() >= 1);
    }
  }

  private static Object visualQuestion(UUID document) throws Exception {
    Class<?> type = Class.forName("com.tikuzhushou.project.ExamProjectExportService$QuestionRow");
    Constructor<?> constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    return constructor.newInstance(1, "A", 1, "SHORT_ANSWER", "简答题", "MEDIUM", 5, "APPROVED",
        Map.of("stem", "观察配图判断设备状态", "options", "", "answer", "正常", "analysis", "观察图形", "scoringRubric", "判断正确得分",
            "stimuli", List.of(Map.of("documentId", document, "page", 1))),
        1, "teacher", "", Instant.now());
  }

  @Test
  void buildsReadableWordAndPdfDeliverables() throws Exception {
    Path font = findFont();
    assumeTrue(font != null, "测试环境没有可嵌入的中文字体");
    ExamProjectExportService service = new ExamProjectExportService(null, new ObjectMapper(), null, null, null, null, font.toString());
    Object header = header();
    Object question = question();
    List<Object> rows = List.of(question);

    byte[] docx = invoke(service, "buildDocx", header, "answer", rows);
    validate(service, "validateDocx", docx, header, "answer", rows);
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
      String text = document.getParagraphs().stream().map(paragraph -> paragraph.getText()).reduce("", (left, right) -> left + right);
      assertTrue(text.contains("测试命题项目"));
      assertTrue(text.contains("设备防护"));
      assertFalse(text.contains("资料来源"));
      assertFalse(text.contains("样题 A"));
      assertFalse(text.contains("snapshot-hash"));
    }

    byte[] pdf = invoke(service, "buildPdf", header, "answer", rows);
    validate(service, "validatePdf", pdf, header, "answer", rows);
    try (PDDocument document = Loader.loadPDF(pdf)) {
      assertTrue(document.getNumberOfPages() > 0);
      String text = new PDFTextStripper().getText(document);
      assertTrue(text.contains("测试命题项目"));
      assertTrue(text.contains("设备防护"));
      assertFalse(text.contains("资料来源"));
      assertFalse(text.contains("样题 A"));
      assertFalse(text.contains("snapshot-hash"));
    }

    byte[] approvalDocx = invoke(service, "buildDocx", header, "approval", rows);
    validate(service, "validateDocx", approvalDocx, header, "approval", rows);
    byte[] approvalPdf = invoke(service, "buildPdf", header, "approval", rows);
    validate(service, "validatePdf", approvalPdf, header, "approval", rows);
    byte[] paperPdf = invoke(service, "buildPdf", header, "paper", rows);
    validate(service, "validatePdf", paperPdf, header, "paper", rows);
  }

  private static Object header() throws Exception {
    Class<?> type = Class.forName("com.tikuzhushou.project.ExamProjectExportService$RunHeader");
    Constructor<?> constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    UUID id = UUID.randomUUID();
    return constructor.newInstance(id, id, id, id, id, 3, "snapshot-hash", "[{\"sourceRole\":\"SAMPLE\",\"name\":\"样题 A\",\"versionRef\":\"v1\"}]", "测试命题项目");
  }

  private static Object question() throws Exception {
    Class<?> type = Class.forName("com.tikuzhushou.project.ExamProjectExportService$QuestionRow");
    Constructor<?> constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    return constructor.newInstance(1, "A", 1, "SINGLE_CHOICE", "单选题", "MEDIUM", 2, "APPROVED",
        Map.of("stem", "设备防护装置确认的首要检查内容是什么？请结合设备启动前检查、作业环境确认、交接记录填写和异常情况处置等要求，判断操作员在发现防护装置状态异常时应当优先核对的事项，并说明这样做与设备安全运行之间的关系。", "options", "A. 防护装置状态 | B. 设备颜色 | C. 操作员年龄 | D. 车间温度",
            "answer", "A", "analysis", "先确认防护装置状态。", "scoringRubric", "答对得 2 分。", "sourceRef", "样题 A"),
        2, "teacher", "已核对资料来源", Instant.parse("2026-09-23T10:00:00Z"));
  }

  @SuppressWarnings("unchecked")
  private static <T> T invoke(Object target, String name, Object... args) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, args[0].getClass(), String.class, List.class);
    method.setAccessible(true);
    return (T) method.invoke(target, args);
  }

  private static void validate(Object target, String name, byte[] bytes, Object header,
      String kind, List<Object> rows) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, byte[].class, header.getClass(), String.class, List.class);
    method.setAccessible(true);
    method.invoke(target, bytes, header, kind, rows);
  }

  private static Path findFont() {
    String configured = System.getenv("APP_EXPORT_PDF_FONT_PATH");
    List<String> candidates = List.of(
        configured == null ? "" : configured,
        "C:\\Windows\\Fonts\\simhei.ttf",
        "C:\\Windows\\Fonts\\Noto Sans SC.ttf",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttf");
    return candidates.stream().filter(value -> !value.isBlank()).map(Path::of).filter(Files::isRegularFile).findFirst().orElse(null);
  }
}
