package com.tikuzhushou.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.ai.DeepSeekService;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgePointServiceTests {
  JdbcTemplate jdbc;
  KnowledgePointService service;
  KnowledgeBaseAccessService access=mock(KnowledgeBaseAccessService.class);
  DeepSeekService ai=mock(DeepSeekService.class);
  UUID base=UUID.randomUUID(),user=UUID.randomUUID(),document=UUID.randomUUID();
  @BeforeEach void setup() {
    var ds=new DriverManagerDataSource("jdbc:h2:mem:points-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
    jdbc=new JdbcTemplate(ds);
    jdbc.execute("create table app_users(id uuid primary key)");
    jdbc.execute("create table knowledge_bases(id uuid primary key,status varchar)");
    jdbc.execute("create table source_documents(id uuid primary key,knowledge_base_id uuid)");
    jdbc.execute("create table document_chunks(document_id uuid,chunk_index int,content text)");
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V46__maintainable_knowledge_points.sql")).execute(ds);
    jdbc.update("insert into app_users values(?)",user);jdbc.update("insert into knowledge_bases values(?,'ACTIVE')",base);
    jdbc.update("insert into source_documents values(?,?)",document,base);
    jdbc.update("insert into document_chunks values(?,0,'投影关系与空间推理')",document);
    when(access.currentUserId()).thenReturn(user);
    service=new KnowledgePointService(jdbc,new ObjectMapper().findAndRegisterModules(),access,ai);
  }
  KnowledgePointService.Edit edit(String status,Integer version){return new KnowledgePointService.Edit("正投影","工程制图","平行投影且投影线垂直投影面","依据三视图判断空间关系","混淆视图的前后关系",status,"MANUAL",List.of(document),version);}
  @Test void versionedCrudArchiveAndRestore(){
    var one=service.create(base,edit("DRAFT",null));
    assertThat(service.confirmedContext(base)).isEmpty();
    var two=service.update(base,one.id(),edit("CONFIRMED",1));
    assertThat(two.version()).isEqualTo(2);assertThat(service.confirmedContext(base)).contains("正投影");
    service.update(base,one.id(),edit("ARCHIVED",2));assertThat(service.confirmedContext(base)).isEmpty();
    service.update(base,one.id(),edit("CONFIRMED",3));assertThat(service.history(base,one.id())).hasSize(4);
  }
  @Test void staleEditNeverOverwrites(){var row=service.create(base,edit("DRAFT",null));service.update(base,row.id(),edit("CONFIRMED",1));assertThatThrownBy(()->service.update(base,row.id(),edit("ARCHIVED",1))).hasMessageContaining("已更新");assertThat(service.list(base).getFirst().status()).isEqualTo("CONFIRMED");}
  @Test void crossBaseDocumentIsRejected(){var input=new KnowledgePointService.Edit("主题","","说明","目标","","DRAFT","MANUAL",List.of(UUID.randomUUID()),null);assertThatThrownBy(()->service.create(base,input)).hasMessageContaining("不属于");assertThat(service.list(base)).isEmpty();}
  @Test void forbiddenOwnerCannotReadOrGenerate(){doThrow(new AccessDeniedException("denied")).when(access).assertKnowledgeBase(base);assertThatThrownBy(()->service.list(base)).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->service.suggest(base,new KnowledgePointService.SuggestRequest(List.of(document)))).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(ai);}
  @Test void aiSuggestionsAreNotPersistedAndSkipExistingTitles(){
    service.create(base,edit("CONFIRMED",null));
    when(ai.analyseJsonFast(anyString(),anyString(),anyInt(),eq("KNOWLEDGE_POINT_DRAFTS"))).thenReturn("{\"points\":[{\"title\":\"正投影\"},{\"title\":\"剖视\",\"description\":\"揭示内部结构\",\"objective\":\"选择合理剖切位置\"}]}");
    var result=service.suggest(base,new KnowledgePointService.SuggestRequest(List.of(document)));
    assertThat(result.points()).hasSize(1);assertThat(result.points().getFirst().status()).isEqualTo("DRAFT");assertThat(service.list(base)).hasSize(1);
  }
  @Test void invalidModelResponseNeverChangesKnowledge(){when(ai.analyseJsonFast(anyString(),anyString(),anyInt(),anyString())).thenReturn("not json");assertThatThrownBy(()->service.suggest(base,new KnowledgePointService.SuggestRequest(List.of(document)))).isInstanceOf(IllegalStateException.class);assertThat(service.list(base)).isEmpty();}
  @Test void archivedBaseIsReadOnly(){jdbc.update("update knowledge_bases set status='RECYCLED' where id=?",base);assertThatThrownBy(()->service.create(base,edit("DRAFT",null))).hasMessageContaining("已归档");}
  @Test void confirmationNeedsObservableObjective(){var input=new KnowledgePointService.Edit("主题","","说明","","","CONFIRMED","MANUAL",List.of(),null);assertThatThrownBy(()->service.create(base,input)).hasMessageContaining("能力目标");}
}
