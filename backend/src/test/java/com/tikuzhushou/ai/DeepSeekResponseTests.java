package com.tikuzhushou.ai;

import com.tikuzhushou.quota.ModelQuotaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeepSeekResponseTests {
  @Test void truncatedEvenSyntacticallyValidJsonIsRejectedAndChargedExactlyOnce() {
    checkFailure("length", "{\"answer\":\"partial\"}", ModelResponseException.Reason.TRUNCATED);
  }

  @Test void emptyVisibleResponseKeepsReasoningUsageAndReportsTypedFailure() {
    checkFailure("stop", "", ModelResponseException.Reason.EMPTY);
  }

  @Test void defaultBudgetSummaryOmitsTokenCapAndThinkingButStillAccountsReportedUsage() throws Exception {
    var builder = RestClient.builder().baseUrl("https://example.org");
    var server = MockRestServiceServer.bindTo(builder).build();
    var config = mock(ModelConfigurationService.class);
    when(config.settings()).thenReturn(new ModelConfigurationService.Settings("test-only", "text", "question", "vision", false));
    var quota = mock(ModelQuotaService.class);
    var reservation = new ModelQuotaService.Reservation(UUID.randomUUID(), LocalDate.now(), 10);
    when(quota.reserve(anyInt())).thenReturn(reservation);
    String result = new ObjectMapper().writeValueAsString(Map.of("summary", "资料主题".repeat(650)));
    String response = new ObjectMapper().writeValueAsString(Map.of(
        "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", result))),
        "usage", Map.of("prompt_tokens", 9000, "completion_tokens", 4000)));
    server.expect(requestTo("https://example.org/chat/completions"))
        .andExpect(jsonPath("$.model").value("text"))
        .andExpect(jsonPath("$.max_tokens").doesNotExist())
        .andExpect(jsonPath("$.thinking.type").value("disabled"))
        .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
        .andExpect(jsonPath("$.response_format.type").value("json_object"))
        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    var client = builder.build();
    var service = new DeepSeekService(client, client, client, client, client, config, quota);
    assertThat(service.analyseJsonFast("system", "user", "ASSESSMENT_INDEX")).isEqualTo(result);
    verify(quota).complete(reservation, result.length(), new ModelQuotaService.Usage(9000, 4000, 0));
    verify(quota).recordEvent(eq(reservation), eq("text"), eq("low"), eq(false), eq("ASSESSMENT_INDEX"),
        eq(result.length()), eq(new ModelQuotaService.Usage(9000, 4000, 0)), anyLong());
    server.verify();
  }

  @Test void providerDefaultBudgetDoesNotAllowTruncatedResponsesThrough() {
    checkFailure("length", "", ModelResponseException.Reason.TRUNCATED, true);
  }

  private void checkFailure(String finish, String content, ModelResponseException.Reason reason) {
    checkFailure(finish, content, reason, false);
  }

  private void checkFailure(String finish, String content, ModelResponseException.Reason reason, boolean providerDefault) {
    var builder = RestClient.builder().baseUrl("https://example.org");
    var server = MockRestServiceServer.bindTo(builder).build();
    var config = mock(ModelConfigurationService.class);
    when(config.settings()).thenReturn(new ModelConfigurationService.Settings("test-only", "text", "question", "vision", false));
    var quota = mock(ModelQuotaService.class);
    var reservation = new ModelQuotaService.Reservation(UUID.randomUUID(), LocalDate.now(), 10);
    when(quota.reserve(anyInt())).thenReturn(reservation);
    server.expect(requestTo("https://example.org/chat/completions"))
        .andExpect(providerDefault ? jsonPath("$.max_tokens").doesNotExist() : jsonPath("$.max_tokens").value(8000))
        .andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"" + finish + "\",\"message\":{\"content\":\""
            + content.replace("\"", "\\\"") + "\"}}],\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":8000,\"completion_tokens_details\":{\"reasoning_tokens\":7990}}}", MediaType.APPLICATION_JSON));
    var client = builder.build();
    var service = new DeepSeekService(client, client, client, client, client, config, quota);
    assertThat(assertThrows(ModelResponseException.class,
        () -> { if (providerDefault) service.analyseJsonFast("system", "user", "ASSESSMENT_V2_SOLVE");
          else service.analyseJson("system", "user", 8000, "low", "ASSESSMENT_V2_SOLVE"); }).reason()).isEqualTo(reason);
    verify(quota, times(1)).complete(eq(reservation), eq(content.length()), eq(new ModelQuotaService.Usage(123, 8000, 7990)));
    verify(quota, times(1)).recordEvent(eq(reservation), eq("text"), eq("low"), eq(!providerDefault), eq("ASSESSMENT_V2_SOLVE"),
        eq(content.length()), eq(new ModelQuotaService.Usage(123, 8000, 7990)), anyLong());
    server.verify();
  }
}
