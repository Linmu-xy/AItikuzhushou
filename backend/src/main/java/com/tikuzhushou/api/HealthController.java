package com.tikuzhushou.api;
import com.tikuzhushou.ai.ModelConfigurationService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api") public class HealthController {
  private final ModelConfigurationService configuration;
  public HealthController(ModelConfigurationService configuration) { this.configuration = configuration; }

  @GetMapping("/health") Map<String,Object> health() {
    var settings = configuration.settings();
    return Map.of("status", "UP", "models", Map.of(
        "text", settings.textModel(), "question", settings.questionModel(),
        "vision", settings.visionModel(), "configured", !settings.apiKey().isBlank()));
  }
}
