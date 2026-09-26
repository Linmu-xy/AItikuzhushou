package com.tikuzhushou.api;
import com.tikuzhushou.quota.ModelQuotaService;import java.util.*;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/model-quota") public class ModelQuotaController {private final ModelQuotaService quota;public ModelQuotaController(ModelQuotaService quota){this.quota=quota;}@GetMapping Map<String,Object> mine(){return quota.mine();}@GetMapping("/breakdown") Object breakdown(){return quota.breakdown();}}
