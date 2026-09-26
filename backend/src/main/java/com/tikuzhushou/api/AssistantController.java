package com.tikuzhushou.api;

import com.tikuzhushou.assistant.AssistantService;
import com.tikuzhushou.assistant.AssistantAttachmentService;
import com.tikuzhushou.audit.AdminAuditService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
  private final AssistantService assistant;
  private final AssistantAttachmentService attachments;
  private final AdminAuditService audit;
  public AssistantController(AssistantService assistant, AssistantAttachmentService attachments, AdminAuditService audit) {
    this.assistant = assistant; this.attachments = attachments; this.audit = audit;
  }

  @GetMapping("/conversations") List<AssistantService.Conversation> conversations() { return assistant.conversations(); }
  @GetMapping("/occupational-standards") List<AssistantService.StandardOption> standards() { return assistant.standards(); }
  @PostMapping("/conversations") AssistantService.Conversation create(@RequestBody(required = false) CreateRequest request) {
    AssistantService.Conversation created = assistant.create(request == null ? null : request.knowledgeBaseId(),
        request == null ? null : request.title(),
        request == null ? null : request.mode(), request == null ? null : request.occupationalStandardId());
    audit.record(AdminAuditService.ASSISTANT_CONVERSATION_CREATE, AdminAuditService.TARGET_ASSISTANT,
        created.id(), created.title(), Map.of("mode", created.mode()));
    return created;
  }
  @GetMapping("/conversations/{id}/messages") List<AssistantService.Message> messages(@PathVariable UUID id) { return assistant.messages(id); }
  @PostMapping("/conversations/{id}/messages") AssistantService.Reply ask(@PathVariable UUID id, @RequestBody AskRequest request) {
    return assistant.ask(id, request.prompt(), request.mode(), request.occupationalStandardId(), request.reasoningEffort(),
        Boolean.TRUE.equals(request.webSearch()));
  }
  @GetMapping("/conversations/{id}/attachments")
  List<AssistantAttachmentService.AttachmentView> attachments(@PathVariable UUID id) { return attachments.list(id); }
  @PostMapping("/conversations/{id}/attachments")
  AssistantAttachmentService.AttachmentView upload(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws java.io.IOException {
    return attachments.upload(id, file);
  }
  @DeleteMapping("/conversations/{id}/attachments/{attachmentId}")
  ResponseEntity<Void> deleteAttachment(@PathVariable UUID id, @PathVariable UUID attachmentId) {
    attachments.delete(id, attachmentId);
    return ResponseEntity.noContent().build();
  }
  @DeleteMapping("/conversations/{id}") ResponseEntity<Void> delete(@PathVariable UUID id) {
    assistant.delete(id);
    audit.record(AdminAuditService.ASSISTANT_CONVERSATION_DELETE, AdminAuditService.TARGET_ASSISTANT, id, null, Map.of());
    return ResponseEntity.noContent().build();
  }

  public record CreateRequest(UUID knowledgeBaseId, String title, String mode, UUID occupationalStandardId) { }
  public record AskRequest(String prompt, String mode, UUID occupationalStandardId, String reasoningEffort,
      Boolean webSearch) { }
}
