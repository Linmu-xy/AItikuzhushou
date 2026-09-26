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
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExamProjectExportFormatTests {
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
