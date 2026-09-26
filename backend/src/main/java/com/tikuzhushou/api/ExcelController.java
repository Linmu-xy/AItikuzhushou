package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.core.CoreBusinessService;
import com.tikuzhushou.excel.QuestionExcelImportService;
import com.tikuzhushou.excel.BlueprintExcelService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/excel")
public class ExcelController {
  private final QuestionExcelImportService importer;
  private final BlueprintExcelService blueprints;
  private final AdminAuditService audit;
  public ExcelController(QuestionExcelImportService importer, BlueprintExcelService blueprints, AdminAuditService audit) {
    this.importer = importer; this.blueprints = blueprints; this.audit = audit;
  }

  @PostMapping(value = "/questions/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  QuestionExcelImportService.ValidationResult validate(@RequestPart MultipartFile file) throws Exception {
    QuestionExcelImportService.ValidationResult result = importer.validate(file);
    audit.record(AdminAuditService.EXCEL_VALIDATE, AdminAuditService.TARGET_EXCEL, null,
        file.getOriginalFilename(),
        Map.of("totalRows", result.totalRows(), "valid", result.valid(),
            "errorCount", result.errorCount(), "warningCount", result.warningCount()));
    return result;
  }

  @PostMapping(value = "/questions/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  CoreBusinessService.Job importQuestions(@RequestPart MultipartFile file) throws Exception {
    CoreBusinessService.Job job = importer.importFile(file);
    audit.record(AdminAuditService.EXCEL_IMPORT, AdminAuditService.TARGET_EXCEL, job.id(),
        file.getOriginalFilename(), Map.of("jobId", job.id()));
    return job;
  }

  @PostMapping(value = "/blueprints/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  CoreBusinessService.Job importBlueprint(@PathVariable UUID id, @RequestPart MultipartFile file) throws Exception {
    CoreBusinessService.Job job = blueprints.importTo(id, file);
    audit.record("BLUEPRINT_EXCEL_IMPORT", AdminAuditService.TARGET_BLUEPRINT, id, file.getOriginalFilename(), Map.of("items", job.result().size()));
    return job;
  }
}
