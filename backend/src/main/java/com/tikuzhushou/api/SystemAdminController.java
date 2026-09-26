package com.tikuzhushou.api;

import com.tikuzhushou.ops.SystemHealthService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/system")
public class SystemAdminController {
  private final SystemHealthService health;
  public SystemAdminController(SystemHealthService health) { this.health = health; }
  @GetMapping("/health") Map<String, Object> health() { return health.inspect(); }
  @GetMapping("/model-configuration") Map<String, Object> modelConfiguration() { return health.modelConfiguration(); }
}
