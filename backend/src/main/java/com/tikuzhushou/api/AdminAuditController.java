package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only semantic audit trail: who did what to which target, before -> after. */
@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {
  private final AdminAuditService audit;
  private final KnowledgeBaseAccessService access;

  public AdminAuditController(AdminAuditService audit, KnowledgeBaseAccessService access) {
    this.audit = audit;
    this.access = access;
  }

  @GetMapping("/operations")
  List<Map<String, Object>> operations(
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String targetType) {
    if (!access.admin()) throw new AccessDeniedException("仅管理员可查看操作审计");
    return audit.list(limit, action, actor, targetType);
  }

  @GetMapping("/operations/page")
  Map<String, Object> page(
      @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset,
      @RequestParam(required = false) String action, @RequestParam(required = false) String actor,
      @RequestParam(required = false) String targetType, @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String risk, @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    if (!access.admin()) throw new AccessDeniedException("仅管理员可查看操作审计");
    return audit.page(limit, offset, action, actor, targetType, outcome, risk, from, to);
  }

  @GetMapping("/summary")
  Map<String, Object> summary(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    if (!access.admin()) throw new AccessDeniedException("仅管理员可查看操作审计");
    return audit.summary(from, to);
  }

  @GetMapping("/actions")
  Map<String, Object> actions() {
    if (!access.admin()) throw new AccessDeniedException("仅管理员可查看操作审计");
    return Map.of(
        "userActions", List.of("USER_CREATE", "USER_UPDATE"),
        "knowledgeActions", List.of("KB_CREATE", "KB_UPDATE", "KB_ARCHIVE", "KB_QUOTA_UPDATE"),
        "documentActions", List.of("DOCUMENT_UPLOAD", "DOCUMENT_PARSE_SUBMIT", "DOCUMENT_PAGE_EDIT",
            "DOCUMENT_TABLE_EDIT", "DOCUMENT_CHUNK_EDIT"),
        "standardActions", List.of("STANDARD_EXTRACT_SUBMIT", "STANDARD_CONFIRM", "STANDARD_UPDATE"),
        "generationActions", List.of("BLUEPRINT_CREATE", "BLUEPRINT_UPDATE", "QUESTIONS_SUBMIT",
            "GENERATION_RETRY", "GENERATION_CANCEL", "EXPORT_SUBMIT", "EXPORT_DOWNLOAD"),
        "reviewActions", List.of("REVIEW_UPDATE"),
        "excelActions", List.of("EXCEL_VALIDATE", "EXCEL_IMPORT"),
        "assistantActions", List.of("ASSISTANT_CONVERSATION_CREATE", "ASSISTANT_CONVERSATION_DELETE"),
        "taskActions", List.of("WORKFLOW_TASK_CANCEL"),
        "modelActions", List.of("MODEL_CONFIG_UPDATE"));
  }
}
