package com.tikuzhushou.api;
import com.tikuzhushou.project.QuestionQualityService;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/exam-projects/{project}/variant-generation-runs/{run}")
public class QuestionQualityController {
  private final QuestionQualityService quality;
  public QuestionQualityController(QuestionQualityService quality){this.quality=quality;}
  @GetMapping("/quality") public List<QuestionQualityService.Inspection> inspect(@PathVariable UUID project,@PathVariable UUID run){return quality.inspect(project,run);}
  @PostMapping("/items/{item}/revision-preview") public QuestionQualityService.Proposal revise(@PathVariable UUID project,@PathVariable UUID run,@PathVariable UUID item,@RequestBody QuestionQualityService.RevisionRequest request){return quality.revise(project,run,item,request);}
}
