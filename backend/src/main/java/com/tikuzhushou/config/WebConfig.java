package com.tikuzhushou.config;

import java.util.Arrays;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {
  @Bean("deepSeekClient")
  RestClient deepSeekClient(@Value("${app.ai.base-url}") String url,
      @Value("${app.ai.read-timeout-seconds:120}") int timeoutSeconds) {
    Duration timeout = Duration.ofSeconds(Math.max(10, Math.min(timeoutSeconds, 300)));
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    var requests = new JdkClientHttpRequestFactory(http);
    requests.setReadTimeout(timeout);
    return RestClient.builder().baseUrl(url).requestFactory(requests).build();
  }

  @Bean("deepSeekAnthropicClient")
  RestClient deepSeekAnthropicClient(
      @Value("${app.ai.web-search-base-url:https://api.deepseek.com/anthropic}") String url) {
    Duration timeout = Duration.ofSeconds(60);
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    var requests = new JdkClientHttpRequestFactory(http);
    requests.setReadTimeout(timeout);
    return RestClient.builder().baseUrl(url).requestFactory(requests).build();
  }

  /**
   * OCR page reconstruction has a strict deadline.  It deliberately uses separate clients so a
   * slow vision page cannot change the timeout of question generation or standard extraction.
   */
  @Bean("visionHighClient")
  RestClient visionHighClient(@Value("${app.ai.base-url}") String url,
      @Value("${app.ocr.high-thinking-timeout-seconds:60}") int timeoutSeconds) {
    return visionClient(url, timeoutSeconds);
  }

  @Bean("visionOcrClient")
  RestClient visionOcrClient(@Value("${app.ai.base-url}") String url,
      @Value("${app.ocr.basic-timeout-seconds:45}") int timeoutSeconds) {
    return visionClient(url, timeoutSeconds);
  }

  @Bean("visionRetryClient")
  RestClient visionRetryClient(@Value("${app.ai.base-url}") String url,
      @Value("${app.ocr.retry-timeout-seconds:45}") int timeoutSeconds) {
    return visionClient(url, timeoutSeconds);
  }

  @Bean("visionPatchClient")
  RestClient visionPatchClient(@Value("${app.ai.base-url}") String url,
      @Value("${app.ocr.patch-timeout-seconds:30}") int timeoutSeconds) {
    return visionClient(url, timeoutSeconds);
  }

  private RestClient visionClient(String url, int timeoutSeconds) {
    Duration timeout = Duration.ofSeconds(Math.max(5, Math.min(timeoutSeconds, 180)));
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    var requests = new JdkClientHttpRequestFactory(http);
    requests.setReadTimeout(timeout);
    return RestClient.builder().baseUrl(url).requestFactory(requests).build();
  }

  @Bean
  WebMvcConfigurer cors(@Value("${app.cors-origins}") String origins) {
    String[] allowedOrigins = Arrays.stream(origins.split(","))
        .map(String::trim).filter(value -> !value.isBlank()).toArray(String[]::new);
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins)
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type", "Idempotency-Key", "Accept")
            .exposedHeaders("Content-Disposition", "Location")
            .maxAge(3600);
      }
    };
  }
}
