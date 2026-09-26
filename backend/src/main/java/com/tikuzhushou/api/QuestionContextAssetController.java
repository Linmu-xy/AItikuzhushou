package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.question.QuestionContextAssetService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrative source governance for local scenario context and authorized exam-pattern summaries. */
@RestController
@RequestMapping("/api")
public class QuestionContextAssetController {
  private final QuestionContextAssetService assets;
  private final AdminAuditService audit;

  public QuestionContextAssetController(QuestionContextAssetService assets, AdminAuditService audit) {
    this.assets = assets;
    this.audit = audit;
  }

  @PostMapping("/knowledge-bases/{knowledgeBaseId}/context-assets")
  Map<String, Object> create(@PathVariable UUID knowledgeBaseId, @RequestBody QuestionContextAssetService.Upsert request) {
    var asset = assets.create(knowledgeBaseId, request);
    audit.record("QUESTION_CONTEXT_ASSET_CREATE", AdminAuditService.TARGET_KB, knowledgeBaseId, asset.title(),
        Map.of("assetId", asset.id(), "type", asset.type(), "status", asset.status()));
    return asset.publicView();
  }

  @PatchMapping("/context-assets/{id}")
  Map<String, Object> update(@PathVariable UUID id, @RequestBody QuestionContextAssetService.Upsert request) {
    var asset = assets.update(id, request);
    audit.record("QUESTION_CONTEXT_ASSET_UPDATE", AdminAuditService.TARGET_KB, asset.knowledgeBaseId(), asset.title(),
        Map.of("assetId", id, "type", asset.type(), "status", asset.status()));
    return asset.publicView();
  }

  @PostMapping("/context-assets/{id}/approve")
  Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) Approval request) {
    var asset = assets.approve(id, request == null ? "APPROVED" : request.decision());
    audit.record("QUESTION_CONTEXT_ASSET_APPROVE", AdminAuditService.TARGET_KB, asset.knowledgeBaseId(), asset.title(),
        Map.of("assetId", id, "status", asset.status()));
    return asset.publicView();
  }

  @GetMapping("/context-assets")
  List<Map<String, Object>> list(@RequestParam UUID knowledgeBaseId) {
    return assets.list(knowledgeBaseId).stream().map(QuestionContextAssetService.Asset::publicView).toList();
  }

  public record Approval(String decision) { }
}
