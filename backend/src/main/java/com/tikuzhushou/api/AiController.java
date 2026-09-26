package com.tikuzhushou.api;
import com.tikuzhushou.ai.DeepSeekService;import java.util.Map;import org.springframework.http.MediaType;import org.springframework.web.bind.annotation.*;import org.springframework.web.multipart.MultipartFile;
@RestController @RequestMapping("/api/ai") public class AiController {
  private final DeepSeekService ai; public AiController(DeepSeekService ai){this.ai=ai;}
  @PostMapping("/text") Map<String,String> text(@RequestBody Map<String,String> body){return Map.of("content",ai.analyseText(body.getOrDefault("prompt","")));}
  @PostMapping(value="/vision",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) Map<String,String> vision(@RequestPart("file") MultipartFile file,@RequestParam(defaultValue="识别图片中的文字、表格、职业标准层级和关键知识点。") String prompt)throws Exception{return Map.of("content",ai.analyseImage(prompt,file.getBytes(),file.getContentType()));}
}
