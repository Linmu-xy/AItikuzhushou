package com.tikuzhushou.excel;

import com.tikuzhushou.core.CoreBusinessService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Excel round trip for a reviewed blueprint; the service delegates all cardinality validation to CoreBusinessService. */
@Service
public class BlueprintExcelService {
  private static final List<String> HEADERS = List.of("序号", "题型", "难度", "职业层级", "考点（多个用；分隔）", "能力目标", "认知目标");
  private final CoreBusinessService core;
  public BlueprintExcelService(CoreBusinessService core) { this.core = core; }

  public byte[] export(UUID jobId) throws IOException {
    var job = core.getJob(jobId);
    if (!"BLUEPRINT".equals(job.type())) throw new IllegalArgumentException("只能导出细目表任务");
    try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("细目表"); Row header = sheet.createRow(0);
      for (int i = 0; i < HEADERS.size(); i++) header.createCell(i).setCellValue(HEADERS.get(i));
      int index = 1;
      for (Map<String, Object> item : job.result()) {
        Row row = sheet.createRow(index++); row.createCell(0).setCellValue(text(item, "sequence"));
        row.createCell(1).setCellValue(text(item, "type")); row.createCell(2).setCellValue(text(item, "difficulty"));
        row.createCell(3).setCellValue(text(item, "level")); row.createCell(4).setCellValue(points(item));
        row.createCell(5).setCellValue(text(item, "abilityObjective")); row.createCell(6).setCellValue(text(item, "cognitiveTarget"));
      }
      for (int i = 0; i < HEADERS.size(); i++) sheet.autoSizeColumn(i);
      workbook.write(out); return out.toByteArray();
    }
  }

  public CoreBusinessService.Job importTo(UUID jobId, MultipartFile file) throws IOException {
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择细目表 Excel 文件");
    try (var workbook = WorkbookFactory.create(file.getInputStream())) {
      Sheet sheet = workbook.getSheet("细目表"); if (sheet == null && workbook.getNumberOfSheets() > 0) sheet = workbook.getSheetAt(0);
      if (sheet == null) throw new IllegalArgumentException("Excel 中没有细目表工作表");
      DataFormatter formatter = new DataFormatter(); Row header = sheet.getRow(0);
      for (int i = 0; i < 5; i++) if (header == null || !HEADERS.get(i).equals(formatter.formatCellValue(header.getCell(i)).trim())) throw new IllegalArgumentException("细目表模板第 " + (i + 1) + " 列应为“" + HEADERS.get(i) + "”");
      List<Map<String, Object>> items = new ArrayList<>();
      for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex); if (row == null || formatter.formatCellValue(row.getCell(0)).isBlank()) continue;
        Map<String, Object> item = new LinkedHashMap<>(); item.put("sequence", number(formatter.formatCellValue(row.getCell(0)), rowIndex));
        item.put("type", formatter.formatCellValue(row.getCell(1)).trim().toUpperCase()); item.put("difficulty", formatter.formatCellValue(row.getCell(2)).trim().toUpperCase());
        item.put("assessmentPoint", formatter.formatCellValue(row.getCell(4)).trim()); items.add(item);
      }
      return core.updateBlueprint(jobId, items);
    } catch (IllegalArgumentException error) { throw error; }
    catch (Exception error) { throw new IllegalArgumentException("无法读取细目表 Excel，请确认文件未损坏且未加密", error); }
  }

  private int number(String raw, int fallback) { try { return Integer.parseInt(raw.trim()); } catch (Exception ignored) { return fallback; } }
  private String text(Map<String, Object> value, String key) { return Objects.toString(value.get(key), ""); }
  private String points(Map<String, Object> item) { Object raw = item.get("assessmentPoints"); if (raw instanceof List<?> list) return String.join("；", list.stream().map(String::valueOf).toList()); return text(item, "assessmentPoint"); }
}
