package com.tikuzhushou.project;

import com.tikuzhushou.question.OpenAssessmentService;
import com.tikuzhushou.question.QuestionRefinementService;
import com.tikuzhushou.question.QuestionProfessionalReviewService;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class QuestionQualityService {
  private final ExamProjectService projects;
  private final ExamProjectVariantGenerationService generation;
  private final ExamProjectGenerationPlanService plans;
  private final QuestionRefinementService refiner;
  private final QuestionProfessionalReviewService reviewer;
  private final Set<UUID> revising=ConcurrentHashMap.newKeySet();
  public QuestionQualityService(ExamProjectService projects,ExamProjectVariantGenerationService generation,
      ExamProjectGenerationPlanService plans,QuestionRefinementService refiner,QuestionProfessionalReviewService reviewer) {
    this.projects=projects;this.generation=generation;this.plans=plans;this.refiner=refiner;this.reviewer=reviewer;
  }
  public List<Inspection> inspect(UUID project,UUID runId) {
    var run=run(project,runId);
    return run.items().stream().map(item->{
      var issues=new ArrayList<>(QuestionQualityChecks.inspect(item.question(),item.questionType(),item.points()));
      if(item.questionVersion()>1) issues.add(new QuestionQualityChecks.Issue("REVIEW_STALE","INFO","AI 审题反馈来自初始版本；请以当前题面重新核对"));
      else if("REVIEW_PENDING".equals(item.status())) issues.add(new QuestionQualityChecks.Issue("AI_UNAVAILABLE","WARNING","AI 审题尚未完成，可以重试审题"));
      else if(Boolean.FALSE.equals(item.review().get("passed"))) issues.add(new QuestionQualityChecks.Issue("AI_REVIEW","WARNING",Objects.toString(item.review().get("feedback"),"AI 提出了质量疑点，请人工核实")));
      var similar=run.items().stream().filter(other->!item.id().equals(other.id()))
          .map(other->new Similar(other.id(),other.variantLabel(),other.sequenceNo(),QuestionQualityChecks.similarity(text(item.question().get("stem")),text(other.question().get("stem")))))
          .filter(other->other.similarity()>=0.72).sorted(Comparator.comparingDouble(Similar::similarity).reversed()).limit(3).toList();
      if(!similar.isEmpty()) issues.add(new QuestionQualityChecks.Issue("SIMILAR","WARNING","本批次有相似题干，请核对是否只替换了数字或措辞（文本相似，不代表语义重复）"));
      return new Inspection(item.id(),item.questionVersion(),issues,similar);
    }).toList();
  }
  /** Returns an independent proposal. Never writes the original question or approves it. */
  public Proposal revise(UUID projectId,UUID runId,UUID itemId,RevisionRequest input) {
    var run=run(projectId,runId); var project=projects.get(projectId);
    var item=run.items().stream().filter(i->i.id().equals(itemId)).findFirst().orElseThrow(()->new IllegalArgumentException("题目不存在"));
    if(input==null || input.expectedVersion()==null || input.expectedVersion()!=item.questionVersion()) throw new IllegalArgumentException("题目版本已改变，请刷新后修订");
    if(!Set.of("REVIEW_REQUIRED","REJECTED").contains(item.status()) || Set.of("QUEUED","RUNNING").contains(run.status())) throw new IllegalArgumentException("请等待生成完成，并重新打开已通过的题目后再修订");
    if(text(input.instruction()).isBlank() || input.instruction().length()>2000) throw new IllegalArgumentException("请填写2000字以内的修订要求");
    if(!revising.add(itemId)) throw new IllegalArgumentException("这道题正在生成修订稿，请勿重复提交");
    try {
      var plan=plans.get(run.generationTaskId()).assessmentPlan();
      Map<String,Object> draft=new LinkedHashMap<>(item.question());
      draft.put("sequence",item.sequenceNo());draft.put("type",item.questionType());draft.put("points",item.points());draft.put("difficulty",item.difficulty());draft.put("level",project.mode());
      draft.put("retryFeedback",input.instruction()); draft.put("_assessmentPreviousQuestion",item.question());
      if(plan!=null && OpenAssessmentService.VERSION.equals(plan.pipelineVersion())) {
        draft.put("_assessmentPipelineVersion",plan.pipelineVersion());draft.put("_assessmentDomainBrief",plan.domainBrief());draft.put("_assessmentCorpusContext",plan.corpusContext());
        draft.put("_assessmentWebSearchEnabled",project.webSearchEnabled());draft.put("_assessmentResearchBudget",new OpenAssessmentService.ResearchBudget(2));
        plan.items().stream().filter(p->p.sequence()==item.sequenceNo()).findFirst().ifPresent(p->{
          draft.put("assessmentPoint",p.competency());draft.put("assessmentTask",p.task());draft.put("_assessmentKnowledgeUse",p.knowledgeUse());draft.put("_assessmentAnswerability",p.answerability());draft.put("_assessmentRequiredMaterial",p.requiredMaterial());
        });
      }
      var candidates=refiner.generateCandidates(List.of(draft),run.generationMode());
      if(candidates.isEmpty() || !candidates.getFirst().hardValid()) throw new IllegalStateException("AI 未返回有效修订稿；原题未改变");
      var candidate=candidates.getFirst();
      if(!candidate.valid()) throw new IllegalStateException("修订稿结构校验未通过："+Objects.toString(candidate.failureReason(),"请调整要求后重试")+"；原题未改变");
      var reviews=reviewer.review(List.of(candidate.question()));
      Map<String,Object> result=new LinkedHashMap<>(refiner.deliveryQuestion(candidate.question()));
      // The old structured rubric cannot silently survive a newly authored textual rubric.
      result.put("scoringItems",List.of()); result.put("qualityChecklist",Map.of());
      var fresh=run(projectId,runId).items().stream().filter(i->i.id().equals(itemId)).findFirst().orElseThrow();
      if(fresh.questionVersion()!=item.questionVersion() || !fresh.status().equals(item.status())) throw new IllegalArgumentException("生成修订稿期间原题已改变，请刷新；未覆盖任何内容");
      return new Proposal(itemId,item.questionVersion(),result,reviews.isEmpty()?null:reviews.getFirst(),QuestionQualityChecks.inspect(result,item.questionType(),item.points()));
    } finally {revising.remove(itemId);}
  }
  private ExamProjectVariantGenerationService.RunView run(UUID project,UUID runId) {
    projects.get(project);var value=generation.get(runId);
    if(!value.projectId().equals(project)) throw new IllegalArgumentException("生成批次不属于此项目");return value;
  }
  private static String text(Object value){return Objects.toString(value,"");}
  public record Similar(UUID id,String variantLabel,int sequenceNo,double similarity) {}
  public record Inspection(UUID id,int version,List<QuestionQualityChecks.Issue> issues,List<Similar> similar) {}
  public record RevisionRequest(Integer expectedVersion,String instruction) {}
  public record Proposal(UUID itemId,int expectedVersion,Map<String,Object> question,QuestionProfessionalReviewService.Review review,List<QuestionQualityChecks.Issue> issues) {}
}
