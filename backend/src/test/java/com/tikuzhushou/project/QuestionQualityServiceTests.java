package com.tikuzhushou.project;
import com.tikuzhushou.question.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class QuestionQualityServiceTests {
  ExamProjectService projects=mock(ExamProjectService.class);
  ExamProjectVariantGenerationService generation=mock(ExamProjectVariantGenerationService.class);
  ExamProjectGenerationPlanService plans=mock(ExamProjectGenerationPlanService.class);
  QuestionRefinementService refiner=mock(QuestionRefinementService.class);
  QuestionProfessionalReviewService reviewer=mock(QuestionProfessionalReviewService.class);
  UUID project=UUID.randomUUID(),run=UUID.randomUUID(),item=UUID.randomUUID(),task=UUID.randomUUID();
  Instant now=Instant.now();
  Map<String,Object> original=new LinkedHashMap<>(Map.of("stem","原题内容","answer","原答案","analysis","解析","scoringRubric","2分"));
  QuestionQualityService service=new QuestionQualityService(projects,generation,plans,refiner,reviewer);
  ExamProjectVariantGenerationService.RunView run(int version,String status) {
    var row=new ExamProjectVariantGenerationService.ItemView(item,UUID.randomUUID(),1,"A",1,"SHORT_ANSWER","简答题","MEDIUM",2,status,1,original,Map.of(),Map.of(),null,null,version,null,null,null,now);
    return new ExamProjectVariantGenerationService.RunView(run,project,task,UUID.randomUUID(),"REVIEW_REQUIRED","FAST",1,1,1,0,0,null,now,now,now,now,List.of(row));
  }
  @BeforeEach void setup(){
    when(projects.get(project)).thenReturn(new ExamProjectService.ProjectView(project,UUID.randomUUID(),UUID.randomUUID(),"测试","KNOWLEDGE_BASE","DRAFT","",1,"{}","[]",true,true,0,now,now,List.of()));
    when(generation.get(run)).thenReturn(run(1,"REVIEW_REQUIRED"));
    var plan=new KnowledgeAssessmentPlanningService.AssessmentPlan("能力目标",List.of(),null,OpenAssessmentService.VERSION,"资料背景","学科背景");
    when(plans.get(task)).thenReturn(new ExamProjectGenerationPlanService.TaskView(task,project,UUID.randomUUID(),"READY",1,1,1,now,now,plan,List.of()));
    when(refiner.generateCandidates(anyList(),anyString())).thenAnswer(inv->{var q=new LinkedHashMap<String,Object>(Map.of("stem","修订题目","answer","修订答案","analysis","完整推导","scoringRubric","2分"));return List.of(new QuestionRefinementService.Candidate(q,true,true,90,null));});
    when(refiner.deliveryQuestion(anyMap())).thenAnswer(inv->new LinkedHashMap<>((Map<String,Object>)inv.getArgument(0)));
    when(reviewer.review(anyList())).thenReturn(List.of(new QuestionProfessionalReviewService.Review(1,true,90,List.of(),"已复核")));
  }
  @Test void proposalNeverMutatesOrApprovesOriginal(){var value=service.revise(project,run,item,new QuestionQualityService.RevisionRequest(1,"优化任务"));assertThat(value.question().get("stem")).isEqualTo("修订题目");assertThat(original.get("stem")).isEqualTo("原题内容");verify(generation,times(2)).get(run);verifyNoMoreInteractions(generation);}
  @Test void staleVersionRejectedBeforePaidCall(){assertThatThrownBy(()->service.revise(project,run,item,new QuestionQualityService.RevisionRequest(2,"优化"))).hasMessageContaining("版本");verifyNoInteractions(refiner,reviewer);}
  @Test void approvedQuestionIsLocked(){when(generation.get(run)).thenReturn(run(1,"APPROVED"));assertThatThrownBy(()->service.revise(project,run,item,new QuestionQualityService.RevisionRequest(1,"优化"))).hasMessageContaining("重新打开");verifyNoInteractions(refiner,reviewer);}
  @Test void concurrentEditInvalidatesSlowProposal(){when(generation.get(run)).thenReturn(run(1,"REVIEW_REQUIRED"),run(2,"REVIEW_REQUIRED"));assertThatThrownBy(()->service.revise(project,run,item,new QuestionQualityService.RevisionRequest(1,"优化"))).hasMessageContaining("原题已改变");}
  @Test void failedCallReleasesSingleFlightGuard(){when(refiner.generateCandidates(anyList(),anyString())).thenThrow(new IllegalStateException("测试上游失败"));assertThatThrownBy(()->service.revise(project,run,item,new QuestionQualityService.RevisionRequest(1,"优化"))).hasMessageContaining("上游失败");assertThatThrownBy(()->service.revise(project,run,item,new QuestionQualityService.RevisionRequest(1,"优化"))).hasMessageContaining("上游失败");verify(refiner,times(2)).generateCandidates(anyList(),anyString());}
  @Test void qualityGetMakesNoModelCalls(){assertThat(service.inspect(project,run)).hasSize(1);verifyNoInteractions(refiner,reviewer);}
}
