package com.tikuzhushou.api;

import com.tikuzhushou.review.QuestionBankService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/question-bank")
public class QuestionBankController {
  private final QuestionBankService bank;

  public QuestionBankController(QuestionBankService bank) { this.bank = bank; }

  @GetMapping
  public QuestionBankService.Page search(
      @RequestParam(required = false) UUID knowledgeBaseId,
      @RequestParam(defaultValue = "false") boolean unlinked,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return bank.search(knowledgeBaseId, unlinked, from, to, q, page, size);
  }
}
