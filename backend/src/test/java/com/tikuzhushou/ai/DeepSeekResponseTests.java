package com.tikuzhushou.ai;

import com.tikuzhushou.quota.ModelQuotaService;
import java.time.LocalDate;
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

  private void checkFailure(String finish, String content, ModelResponseException.Reason reason) {
    var builder = RestClient.builder().baseUrl("https://example.org");
    var server = MockRestServiceServer.bindTo(builder).build();
    var config = mock(ModelConfigurationService.class);
    when(config.settings()).thenReturn(new ModelConfigurationService.Settings("test-only", "text", "question", "vision", false));
    var quota = mock(ModelQuotaService.class);
    var reservation = new ModelQuotaService.Reservation(UUID.randomUUID(), LocalDate.now(), 10);
    when(quota.reserve(anyInt())).thenReturn(reservation);
    server.expect(requestTo("https://example.org/chat/completions"))
        .andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"" + finish + "\",\"message\":{\"content\":\""
            + content.replace("\"", "\\\"") + "\"}}],\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":8000,\"completion_tokens_details\":{\"reasoning_tokens\":7990}}}", MediaType.APPLICATION_JSON));
    var client = builder.build();
    var service = new DeepSeekService(client, client, client, client, client, config, quota);
    assertThat(assertThrows(ModelResponseException.class,
        () -> service.analyseJson("system", "user", 8000, "low", "ASSESSMENT_V2_SOLVE")).reason()).isEqualTo(reason);
    verify(quota, times(1)).complete(eq(reservation), eq(content.length()), eq(new ModelQuotaService.Usage(123, 8000, 7990)));
    verify(quota, times(1)).recordEvent(eq(reservation), eq("text"), eq("low"), eq(true), eq("ASSESSMENT_V2_SOLVE"),
        eq(content.length()), eq(new ModelQuotaService.Usage(123, 8000, 7990)), anyLong());
    server.verify();
  }
}
