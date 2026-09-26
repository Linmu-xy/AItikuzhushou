package com.tikuzhushou.api;

import com.tikuzhushou.ai.ModelConfigurationService;
import com.tikuzhushou.audit.AdminAuditService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only endpoint: it never returns a full provider credential. */
@RestController
@RequestMapping("/api/admin/model-configuration")
public class ModelConfigurationController {
  private final ModelConfigurationService configuration;
  private final AdminAuditService audit;
  public ModelConfigurationController(ModelConfigurationService configuration, AdminAuditService audit) {
    this.configuration = configuration; this.audit = audit;
  }

  @GetMapping Map<String, Object> get() { return configuration.publicConfiguration(); }
  @PutMapping Map<String, Object> update(@RequestBody Update request, Authentication authentication) {
    Map<String, Object> before = configuration.publicConfiguration();
    configuration.update(request.apiKey(), request.textModel(), request.questionModel(), request.visionModel(), authentication.getName());
    Map<String, Object> after = configuration.publicConfiguration();
    String maskedBefore = String.valueOf(before.get("apiKeyMasked"));
    String maskedAfter = String.valueOf(after.get("apiKeyMasked"));
    audit.record(AdminAuditService.MODEL_CONFIG_UPDATE, AdminAuditService.TARGET_MODEL_CONFIG, null, "DEEPSEEK",
        Map.of("changes", java.util.List.of(
            Map.of("field", "textModel", "from", before.get("textModel"), "to", after.get("textModel")),
            Map.of("field", "questionModel", "from", before.get("questionModel"), "to", after.get("questionModel")),
            Map.of("field", "visionModel", "from", before.get("visionModel"), "to", after.get("visionModel")),
            Map.of("field", "apiKey", "rotated", !maskedBefore.equals(maskedAfter)))));
    return after;
  }
  public record Update(String apiKey, String textModel, String questionModel, String visionModel) { }
}
