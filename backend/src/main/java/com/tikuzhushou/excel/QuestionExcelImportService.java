package com.tikuzhushou.excel;

import com.tikuzhushou.core.CoreBusinessService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class QuestionExcelImportService {
  private static final List<String> HEADERS = List.of("序号", "题型", "考点", "难度", "题干", "选项", "答案", "解析", "原文定位", "原文片段");
  private static final Set<String> TYPES = Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "FILL_BLANK",
      "SHORT_ANSWER", "CALCULATION", "ESSAY", "CASE_ANALYSIS", "COMPREHENSIVE");
  private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
  private final CoreBusinessService core;

  public QuestionExcelImportService(CoreBusinessService core) { this.core = core; }

  public ValidationResult validate(MultipartFile file) throws IOException {
    return inspect(parse(file).rows());
  }

  public CoreBusinessService.Job importFile(MultipartFile file) throws IOException {
    Parsed parsed = parse(file); ValidationResult result = inspect(parsed.rows());
    if (!result.valid()) {
      String first = result.issues().stream().filter(issue -> "ERROR".equals(issue.severity()))
          .findFirst().map(Issue::message).orElse("Excel 内容不符合导入要求");
      throw new IllegalArgumentException("EXCEL_VALIDATION_FAILED：" + first);
    }
    return core.importQuestions(parsed.rows());
  }

  private Parsed parse(MultipartFile file) throws IOException {
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择 Excel 文件");
    String name = Objects.requireNonNullElse(file.getOriginalFilename(), "");
    if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new IllegalArgumentException("仅支持 .xlsx 格式");
    if (file.getSize() > 20L * 1024 * 1024) throw new IllegalArgumentException("Excel 文件不能超过 20MB");
    try (var workbook = WorkbookFactory.create(file.getInputStream())) {
      Sheet sheet = workbook.getSheet("题库");
      if (sheet == null && workbook.getNumberOfSheets() > 0) sheet = workbook.getSheetAt(0);
      if (sheet == null) throw new IllegalArgumentException("Excel 中没有工作表");
      DataFormatter formatter = new DataFormatter(); Row header = sheet.getRow(0);
      for (int index = 0; index < HEADERS.size(); index++) {
        if (header == null || !HEADERS.get(index).equals(formatter.formatCellValue(header.getCell(index)).trim())) {
          throw new IllegalArgumentException("模板表头不匹配，第 " + (index + 1) + " 列应为“" + HEADERS.get(index) + "”");
        }
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (row == null || formatter.formatCellValue(row.getCell(4)).isBlank()) continue;
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("sequence", number(formatter.formatCellValue(row.getCell(0)), rowIndex));
        question.put("type", formatter.formatCellValue(row.getCell(1)).trim().toUpperCase(Locale.ROOT));
        question.put("assessmentPoint", formatter.formatCellValue(row.getCell(2)).trim());
        question.put("difficulty", formatter.formatCellValue(row.getCell(3)).trim().toUpperCase(Locale.ROOT));
        question.put("stem", formatter.formatCellValue(row.getCell(4)).trim());
        question.put("options", formatter.formatCellValue(row.getCell(5)).trim());
        question.put("answer", formatter.formatCellValue(row.getCell(6)).trim());
        question.put("analysis", formatter.formatCellValue(row.getCell(7)).trim());
        question.put("sourceRef", formatter.formatCellValue(row.getCell(8)).trim());
        question.put("sourceExcerpt", formatter.formatCellValue(row.getCell(9)).trim());
        rows.add(question);
      }
      return new Parsed(List.copyOf(rows));
    } catch (IllegalArgumentException error) { throw error; }
    catch (Exception error) { throw new IllegalArgumentException("无法读取 Excel，请确认文件未损坏且没有加密", error); }
  }

  private ValidationResult inspect(List<Map<String, Object>> rows) {
    List<Issue> issues = new ArrayList<>(); Set<Integer> sequences = new HashSet<>(); Set<String> stems = new LinkedHashSet<>();
    if (rows.isEmpty()) issues.add(new Issue(0, "ERROR", "EMPTY_WORKBOOK", "题库工作表没有可导入题目"));
    if (rows.size() > 1000) issues.add(new Issue(0, "ERROR", "TOO_MANY_ROWS", "单次最多导入 1000 道题"));
    for (int index = 0; index < rows.size(); index++) {
      Map<String, Object> row = rows.get(index); int excelRow = index + 2; int sequence = (int) row.get("sequence");
      String type = value(row, "type"), difficulty = value(row, "difficulty"), stem = value(row, "stem");
      if (!sequences.add(sequence)) issues.add(new Issue(excelRow, "ERROR", "DUPLICATE_SEQUENCE", "序号 " + sequence + " 重复"));
      if (!TYPES.contains(type)) issues.add(new Issue(excelRow, "ERROR", "INVALID_TYPE", "题型不在九种支持范围内：" + type));
      if (!DIFFICULTIES.contains(difficulty)) issues.add(new Issue(excelRow, "ERROR", "INVALID_DIFFICULTY", "难度必须为 EASY、MEDIUM 或 HARD"));
      for (String field : List.of("assessmentPoint", "stem", "answer", "analysis")) {
        if (value(row, field).isBlank()) issues.add(new Issue(excelRow, "ERROR", "REQUIRED_FIELD_MISSING", field + " 不能为空"));
      }
      String normalized = stem.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]", "");
      if (!normalized.isBlank() && !stems.add(normalized)) issues.add(new Issue(excelRow, "ERROR", "DUPLICATE_STEM", "题干与前序题目重复"));
      if (("SINGLE_CHOICE".equals(type) || "MULTIPLE_CHOICE".equals(type)) && value(row, "options").split("\\s*\\|\\s*").length != 4) {
        issues.add(new Issue(excelRow, "ERROR", "INVALID_OPTIONS", "选择题必须包含 4 个以 | 分隔的选项"));
      }
      if (value(row, "sourceRef").isBlank() || value(row, "sourceExcerpt").isBlank()) {
        issues.add(new Issue(excelRow, "WARNING", "SOURCE_MISSING", "缺少原文定位或片段，导入后质量验收可能不通过"));
      }
    }
    int errors = (int) issues.stream().filter(issue -> "ERROR".equals(issue.severity())).count();
    int warnings = issues.size() - errors;
    return new ValidationResult(rows.size(), errors == 0, errors, warnings, List.copyOf(issues), rows.stream().limit(20).toList());
  }

  private String value(Map<String, Object> row, String key) { return Objects.toString(row.get(key), "").trim(); }
  private int number(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; } }

  private record Parsed(List<Map<String, Object>> rows) { }
  public record Issue(int row, String severity, String code, String message) { }
  public record ValidationResult(int totalRows, boolean valid, int errorCount, int warningCount,
      List<Issue> issues, List<Map<String, Object>> preview) { }
}
