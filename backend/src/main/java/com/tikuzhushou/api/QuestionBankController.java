package com.tikuzhushou.api;

import com.tikuzhushou.review.QuestionBankService;
import com.tikuzhushou.review.UserQuestionBankService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/question-bank")
public class QuestionBankController {
  private final UserQuestionBankService banks;
  public QuestionBankController(UserQuestionBankService banks) { this.banks = banks; }

  @GetMapping
  public List<UserQuestionBankService.BankSummary> list() { return banks.list(); }

  @PostMapping
  public UserQuestionBankService.BankSummary create(@RequestBody UserQuestionBankService.BankInput input) { return banks.create(input); }

  @GetMapping("/available/questions")
  public QuestionBankService.Page questions(
      @RequestParam(required = false) UUID knowledgeBaseId,
      @RequestParam(defaultValue = "false") boolean unlinked,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return banks.availableQuestions(knowledgeBaseId, unlinked, from, to, q, page, size);
  }

  @GetMapping("/available/papers")
  public List<UserQuestionBankService.PaperCandidate> papers(@RequestParam(defaultValue = "") String q) { return banks.availablePapers(q); }

  @GetMapping("/available/content")
  public UserQuestionBankService.AvailableContent content(@RequestParam(defaultValue = "") String q) { return banks.availableContent(q); }

  @GetMapping("/banks/{bankId}")
  public UserQuestionBankService.BankDetail detail(@PathVariable UUID bankId) { return banks.detail(bankId); }

  @PutMapping("/banks/{bankId}")
  public UserQuestionBankService.BankSummary update(@PathVariable UUID bankId,
      @RequestBody UserQuestionBankService.BankInput input) { return banks.update(bankId, input); }

  @DeleteMapping("/banks/{bankId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID bankId) { banks.delete(bankId); }

  @PostMapping("/banks/{bankId}/questions")
  public AddedCount addQuestions(@PathVariable UUID bankId,
      @RequestBody UserQuestionBankService.AddQuestions input) {
    return new AddedCount(banks.addQuestions(bankId, input));
  }

  @PostMapping("/banks/{bankId}/papers")
  public AddedCount addPaper(@PathVariable UUID bankId,
      @RequestBody UserQuestionBankService.AddPaper input) {
    return new AddedCount(banks.addPaper(bankId, input) ? 1 : 0);
  }

  @DeleteMapping("/banks/{bankId}/entries/{entryId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeEntry(@PathVariable UUID bankId, @PathVariable UUID entryId) { banks.removeEntry(bankId, entryId); }

  public record AddedCount(int added) { }
}
