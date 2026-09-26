package com.tikuzhushou.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tikuzhushou.ai.ModelConfigurationService;
import com.tikuzhushou.quota.ModelQuotaService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DeepSeekWebSearchServiceTest {
  @Test void sendsAnthropicWebToolAndAccountsForProviderUsage() {
    var builder = RestClient.builder().baseUrl("https://api.deepseek.com/anthropic");
    var server = MockRestServiceServer.bindTo(builder).build();
    var configuration = mock(ModelConfigurationService.class);
    when(configuration.settings()).thenReturn(new ModelConfigurationService.Settings(
        "test-key", "deepseek-flash", "deepseek-flash", "deepseek-flash", false));
    var quota = mock(ModelQuotaService.class);
    var reservation = new ModelQuotaService.Reservation(UUID.randomUUID(), LocalDate.now(), 8);
    when(quota.reserve(anyInt())).thenReturn(reservation);
    server.expect(requestTo("https://api.deepseek.com/anthropic/v1/messages"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("x-api-key", "test-key"))
        .andExpect(content().string(containsString("web_search_20250305")))
        .andRespond(withSuccess("""
            {"content":[{"type":"server_tool_use","name":"web_search"},
              {"type":"web_search_tool_result","content":[
                {"type":"web_search_result","title":"官方更新日志","url":"https://api-docs.deepseek.com/updates/#1"}]},
              {"type":"text","text":"官方更新日志已更新。"}],
             "stop_reason":"end_turn","usage":{"input_tokens":8123,"output_tokens":120}}
            """, MediaType.APPLICATION_JSON));
    var answer = new DeepSeekWebSearchService(builder.build(), configuration, quota).search("查找更新日志");
    server.verify();
    assertEquals(1, answer.sources().size());
    assertEquals("https://api-docs.deepseek.com/updates/", answer.sources().getFirst().url());
    verify(quota).complete(eq(reservation), eq(answer.text().length()),
        eq(new ModelQuotaService.Usage(8123, 120, 0)));
  }

  @Test void acceptsOnlyActualWebToolResultsWithSafeUrls() {
    Map<String, Object> response = Map.of("content", List.of(
        Map.of("type", "server_tool_use", "name", "web_search"),
        Map.of("type", "web_search_tool_result", "content", List.of(
            Map.of("type", "web_search_result", "title", "官方更新日志", "url", "https://api-docs.deepseek.com/updates/#1"),
            Map.of("type", "web_search_result", "title", "重复页面", "url", "https://api-docs.deepseek.com/updates/?tracking=1"),
            Map.of("type", "web_search_result", "title", "危险链接", "url", "javascript:alert(1)"))),
        Map.of("type", "text", "text", "已检索到更新日志。")));
    var answer = DeepSeekWebSearchService.parse(response);
    assertEquals("已检索到更新日志。", answer.text());
    assertEquals(1, answer.sources().size());
    assertEquals("https://api-docs.deepseek.com/updates/", answer.sources().getFirst().url());
  }

  @Test void assessmentSearchUsesEvidencePromptAndSeparateUsageCategory() {
    var builder = RestClient.builder().baseUrl("https://api.deepseek.com/anthropic");
    var server = MockRestServiceServer.bindTo(builder).build();
    var configuration = mock(ModelConfigurationService.class);
    when(configuration.settings()).thenReturn(new ModelConfigurationService.Settings(
        "test-key", "deepseek-flash", "deepseek-flash", "deepseek-flash", false));
    var quota = mock(ModelQuotaService.class);
    var reservation = new ModelQuotaService.Reservation(UUID.randomUUID(), LocalDate.now(), 8);
    when(quota.reserve(anyInt())).thenReturn(reservation);
    server.expect(requestTo("https://api.deepseek.com/anthropic/v1/messages"))
        .andExpect(content().string(containsString("每条事实附实际检索到的网页 URL")))
        .andExpect(content().json("{\"thinking\":{\"type\":\"disabled\"},\"max_tokens\":3000}"))
        .andRespond(withSuccess("""
            {"content":[{"type":"server_tool_use","name":"web_search"},
              {"type":"web_search_tool_result","content":[{"type":"web_search_result","title":"技术标准","url":"https://example.org/spec"}]},
              {"type":"text","text":"事实一 https://example.org/spec"}],"stop_reason":"end_turn"}
            """, MediaType.APPLICATION_JSON));
    new DeepSeekWebSearchService(builder.build(), configuration, quota).searchForAssessment("公开技术主题");
    server.verify();
    verify(quota).recordEvent(eq(reservation), eq("deepseek-flash"), eq("low"), eq(false),
        eq("QUESTION_WEB_SEARCH"), anyInt(), eq(ModelQuotaService.Usage.EMPTY), org.mockito.ArgumentMatchers.anyLong());
  }

  @Test void rejectsTextThatDidNotActuallySearch() {
    Map<String, Object> response = Map.of("content", List.of(Map.of("type", "text", "text", "我搜索到了答案")));
    assertThrows(IllegalStateException.class, () -> DeepSeekWebSearchService.parse(response));
  }

  @Test void rejectsToolCallWithoutVerifiableResults() {
    Map<String, Object> response = Map.of("content", List.of(
        Map.of("type", "server_tool_use", "name", "web_search"),
        Map.of("type", "web_search_tool_result", "content", List.of()),
        Map.of("type", "text", "text", "这是一个答案")));
    assertThrows(IllegalStateException.class, () -> DeepSeekWebSearchService.parse(response));
  }
}
