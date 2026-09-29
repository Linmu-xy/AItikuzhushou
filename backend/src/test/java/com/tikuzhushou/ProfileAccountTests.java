package com.tikuzhushou;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.ai.DeepSeekService;
import com.tikuzhushou.assistant.DeepSeekWebSearchService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"APP_BOOTSTRAP_ADMIN_PASSWORD=test-only-password",
    "spring.datasource.url=jdbc:h2:mem:profile-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "app.queue.provider=local"})
@AutoConfigureMockMvc
class ProfileAccountTests {
  @Autowired MockMvc mvc;
  @MockBean DeepSeekService deepSeek;
  @MockBean DeepSeekWebSearchService webSearch;
  @TempDir static java.nio.file.Path storage;
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.storage.local-path", () -> storage.toString());
  }
  private final ObjectMapper mapper = new ObjectMapper();

  private String newUser() throws Exception {
    String username = "profile-" + UUID.randomUUID().toString().substring(0, 8);
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("username", username, "password", "profile-test-password"))))
        .andExpect(status().isOk());
    return username;
  }
  private MockHttpSession login(String username) throws Exception {
    return (MockHttpSession) mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("username", username, "password", "profile-test-password"))))
        .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  }

  @Test void personalSettingsRequireAuthentication() throws Exception {
    for (String endpoint : List.of("display-name", "settings", "password")) {
      mvc.perform(put("/api/auth/profile/" + endpoint).contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(get("/api/auth/avatar")).andExpect(status().isUnauthorized());
    mvc.perform(delete("/api/auth/avatar")).andExpect(status().isUnauthorized());
  }

  @Test void displayNameAndPreferencesPersistAndStayIsolated() throws Exception {
    String username = newUser(), other = newUser();
    MockHttpSession session = login(username);
    mvc.perform(put("/api/auth/profile/display-name").session(session).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("displayName", "  林老师  "))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("林老师"));
    mvc.perform(put("/api/auth/profile/settings").session(session).contentType(MediaType.APPLICATION_JSON)
        .content("{\"assistantEffort\":\"DEEP\",\"assistantWebSearch\":true}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("林老师"))
        .andExpect(jsonPath("$.assistantEffort").value("DEEP")).andExpect(jsonPath("$.assistantWebSearch").value(true));
    mvc.perform(get("/api/auth/me").session(login(username))).andExpect(status().isOk())
        .andExpect(jsonPath("$.assistantEffort").value("DEEP")).andExpect(jsonPath("$.assistantWebSearch").value(true));
    mvc.perform(get("/api/auth/me").session(login(other))).andExpect(status().isOk())
        .andExpect(jsonPath("$.assistantEffort").value("STANDARD")).andExpect(jsonPath("$.displayName").value(other));
    mvc.perform(put("/api/auth/profile/settings").session(session).contentType(MediaType.APPLICATION_JSON)
        .content("{\"assistantEffort\":\"INVALID\",\"assistantWebSearch\":false}")).andExpect(status().isBadRequest());
    mvc.perform(put("/api/auth/profile/display-name").session(session).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("displayName", "名".repeat(41))))).andExpect(status().isBadRequest());
    mvc.perform(put("/api/auth/profile/display-name").session(session).contentType(MediaType.APPLICATION_JSON)
        .content("{\"displayName\":\" \"}")).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value(username));
    mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
        .andExpect(jsonPath("$.assistantEffort").value("DEEP")).andExpect(jsonPath("$.assistantWebSearch").value(true));
  }

  @Test void passwordChangeValidatesInputAndRotatesOtherSessions() throws Exception {
    String username = newUser();
    MockHttpSession current = login(username), other = login(username);
    for (Map<String, String> invalid : List.of(
        Map.of("currentPassword", "incorrect", "newPassword", "next-password"),
        Map.of("currentPassword", "profile-test-password", "newPassword", "short"),
        Map.of("currentPassword", "profile-test-password", "newPassword", "profile-test-password"),
        Map.of("currentPassword", "profile-test-password", "newPassword", "密".repeat(25)))) {
      mvc.perform(put("/api/auth/profile/password").session(current).contentType(MediaType.APPLICATION_JSON)
          .content(mapper.writeValueAsString(invalid))).andExpect(status().isBadRequest());
    }
    mvc.perform(get("/api/auth/me").session(other)).andExpect(status().isOk());
    var changed = mvc.perform(put("/api/auth/profile/password").session(current).contentType(MediaType.APPLICATION_JSON)
        .content("{\"currentPassword\":\"profile-test-password\",\"newPassword\":\"next-password\"}"))
        .andExpect(status().isOk()).andReturn();
    MockHttpSession refreshed = (MockHttpSession) changed.getRequest().getSession(false);
    assertNotNull(refreshed); assertNotSame(current, refreshed);
    mvc.perform(get("/api/auth/me").session(refreshed)).andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").session(other)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("username", username, "password", "profile-test-password"))))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("username", username, "password", "next-password"))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(username));
  }

  @Test void avatarIsNormalizedPrivateReplaceableAndRemovable() throws Exception {
    String username = newUser(), other = newUser();
    MockHttpSession session = login(username);
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(480, 320, BufferedImage.TYPE_INT_RGB), "png", output);
    var file = new MockMultipartFile("file", "portrait.png", "image/png", output.toByteArray());
    var first = mvc.perform(multipart("/api/auth/avatar").file(file).session(session)).andExpect(status().isOk())
        .andExpect(jsonPath("$.avatarUrl").value(startsWith("/api/auth/avatar?v="))).andReturn();
    String initialUrl = mapper.readTree(first.getResponse().getContentAsString()).path("avatarUrl").asText();
    var image = mvc.perform(get("/api/auth/avatar").session(session)).andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn();
    var decoded = ImageIO.read(new ByteArrayInputStream(image.getResponse().getContentAsByteArray()));
    assertEquals(256, decoded.getWidth()); assertEquals(256, decoded.getHeight());
    mvc.perform(get("/api/auth/avatar").session(login(other))).andExpect(status().isNotFound());
    var second = mvc.perform(multipart("/api/auth/avatar").file(file).session(session)).andExpect(status().isOk()).andReturn();
    assertNotEquals(initialUrl, mapper.readTree(second.getResponse().getContentAsString()).path("avatarUrl").asText());
    mvc.perform(delete("/api/auth/avatar").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").value(""));
    mvc.perform(get("/api/auth/avatar").session(session)).andExpect(status().isNotFound());
    mvc.perform(delete("/api/auth/avatar").session(session)).andExpect(status().isOk());
  }

  @Test void avatarRejectsOversizedCorruptAndDisguisedFiles() throws Exception {
    MockHttpSession session = login(newUser());
    for (MockMultipartFile file : List.of(
        new MockMultipartFile("file", "empty.png", "image/png", new byte[0]),
        new MockMultipartFile("file", "large.png", "image/png", new byte[2 * 1024 * 1024 + 1]),
        new MockMultipartFile("file", "corrupt.png", "image/png", "not an image".getBytes()),
        new MockMultipartFile("file", "vector.svg", "image/svg+xml", "<svg/>".getBytes()))) {
      mvc.perform(multipart("/api/auth/avatar").file(file).session(session)).andExpect(status().isBadRequest());
    }
    var gif = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "gif", gif);
    mvc.perform(multipart("/api/auth/avatar").file(new MockMultipartFile("file", "pretend.png", "image/png", gif.toByteArray())).session(session))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").value(""));
  }
}
