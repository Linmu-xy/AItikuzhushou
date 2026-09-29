package com.tikuzhushou.api;

import com.tikuzhushou.knowledge.KnowledgePointService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge-bases/{base}/points")
public class KnowledgePointController {
  private final KnowledgePointService points;
  public KnowledgePointController(KnowledgePointService points) {this.points=points;}
  @GetMapping public List<KnowledgePointService.Point> list(@PathVariable UUID base) {return points.list(base);}
  @PostMapping public KnowledgePointService.Point create(@PathVariable UUID base,@RequestBody KnowledgePointService.Edit input) {return points.create(base,input);}
  @PutMapping("/{id}") public KnowledgePointService.Point update(@PathVariable UUID base,@PathVariable UUID id,@RequestBody KnowledgePointService.Edit input) {return points.update(base,id,input);}
  @GetMapping("/{id}/versions") public List<Map<String,Object>> history(@PathVariable UUID base,@PathVariable UUID id) {return points.history(base,id);}
  @PostMapping("/suggestions") public KnowledgePointService.Suggestions suggest(@PathVariable UUID base,@RequestBody KnowledgePointService.SuggestRequest input) {return points.suggest(base,input);}
}
