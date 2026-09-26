package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.document.DocumentIntakeService;
import com.tikuzhushou.document.DocumentParsingService;
import com.tikuzhushou.workflow.DocumentWorkflowWorker;
import com.tikuzhushou.workflow.WorkflowTaskService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
  private final DocumentIntakeService intake;
  private final DocumentParsingService parsing;
  private final DocumentWorkflowWorker worker;
  private final AdminAuditService audit;

  public DocumentController(DocumentIntakeService intake, DocumentParsingService parsing,
      DocumentWorkflowWorker worker, AdminAuditService audit) {
    this.intake = intake;
    this.parsing = parsing;
    this.worker = worker;
    this.audit = audit;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Map<String, Object> upload(@RequestParam UUID knowledgeBaseId, @RequestPart MultipartFile file) throws Exception {
    var document = intake.store(knowledgeBaseId, file);
    audit.record(AdminAuditService.DOCUMENT_UPLOAD, AdminAuditService.TARGET_DOCUMENT, document.id(),
        document.name(), Map.of("knowledgeBaseId", knowledgeBaseId, "sizeBytes", document.size()));
    return Map.of("document", document, "statusCode", "UPLOADED",
        "nextStep", "文件已完成对象存储写入和数据库校验，可以提交后台解析任务。");
  }

  @PostMapping("/{id}/parse-jobs")
  ResponseEntity<WorkflowTaskService.Task> submitParse(@PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    WorkflowTaskService.Task task = worker.submit(id, idempotencyKey);
    audit.record(AdminAuditService.DOCUMENT_PARSE_SUBMIT, AdminAuditService.TARGET_DOCUMENT, id, null,
        Map.of("taskId", task.id()));
    return ResponseEntity.accepted().body(task);
  }

  /** Backward-compatible synchronous endpoint; the web client uses parse-jobs. */
  @PostMapping("/{id}/parse")
  DocumentParsingService.ParsedDocument parse(@PathVariable UUID id) throws Exception { return parsing.parse(id); }

  @GetMapping("/{id}/parsed")
  DocumentParsingService.ParsedDocument parsed(@PathVariable UUID id) { return parsing.get(id); }

  @GetMapping("/{id}/chunks")
  List<DocumentParsingService.EditableChunk> chunks(@PathVariable UUID id,
      @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
    return parsing.chunks(id, offset, limit);
  }

  @GetMapping("/{id}/tables")
  List<DocumentParsingService.ParsedTable> tables(@PathVariable UUID id) { return parsing.tables(id); }

  @GetMapping("/{id}/pages")
  List<DocumentParsingService.MarkdownPage> pages(@PathVariable UUID id) { return parsing.pages(id); }

  @GetMapping("/{id}/pages/{page}/content")
  DocumentParsingService.PageContent pageContent(@PathVariable UUID id, @PathVariable int page) {
    return parsing.pageContent(id, page);
  }

  @GetMapping("/{id}/pages/{page}/versions")
  List<DocumentParsingService.PageVersion> pageVersions(@PathVariable UUID id, @PathVariable int page) {
    return parsing.pageVersions(id, page);
  }

  @PatchMapping("/{id}/pages/{page}")
  DocumentParsingService.PageUpdateResult updatePage(@PathVariable UUID id, @PathVariable int page,
      @RequestBody PageUpdate update, Authentication authentication) {
    DocumentParsingService.PageUpdateResult result = parsing.updatePage(id, page, update.markdown(), update.confirmed(),
        update.rebuildVectors(), authentication.getName());
    audit.record(AdminAuditService.DOCUMENT_PAGE_EDIT, AdminAuditService.TARGET_DOCUMENT, id, null,
        Map.of("page", page, "confirmed", update.confirmed(), "rebuildVectors", update.rebuildVectors()));
    return result;
  }

  @PatchMapping("/{documentId}/tables/{tableId}")
  DocumentParsingService.ParsedTable updateTable(@PathVariable UUID documentId, @PathVariable UUID tableId,
      @RequestBody TableUpdate update) {
    DocumentParsingService.ParsedTable result = parsing.updateTable(documentId, tableId, update.markdown());
    audit.record(AdminAuditService.DOCUMENT_TABLE_EDIT, AdminAuditService.TARGET_DOCUMENT, documentId, null,
        Map.of("tableId", tableId));
    return result;
  }

  @GetMapping(value = "/{id}/pages/{page}/image", produces = MediaType.IMAGE_PNG_VALUE)
  ResponseEntity<byte[]> pageImage(@PathVariable UUID id, @PathVariable int page) {
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(parsing.renderPage(id, page));
  }

  @PatchMapping("/{documentId}/chunks/{chunkId}")
  DocumentParsingService.EditableChunk updateChunk(@PathVariable UUID documentId,
      @PathVariable UUID chunkId, @RequestBody ChunkUpdate update) {
    DocumentParsingService.EditableChunk result = parsing.updateChunk(documentId, chunkId, update.content());
    audit.record(AdminAuditService.DOCUMENT_CHUNK_EDIT, AdminAuditService.TARGET_DOCUMENT, documentId, null,
        Map.of("chunkId", chunkId));
    return result;
  }

  public record ChunkUpdate(String content) { }
  public record TableUpdate(String markdown) { }
  public record PageUpdate(String markdown, boolean confirmed, boolean rebuildVectors) { }
}
