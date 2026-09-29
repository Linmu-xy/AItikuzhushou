package com.tikuzhushou.api;

import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.document.ObjectStorageService;
import com.tikuzhushou.identity.AppUserService;
import com.tikuzhushou.identity.EmailVerificationService;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.context.SecurityContextRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthControllerAvatarTests {
  @Test void committedAvatarRemovalRemainsSuccessfulWhenOldObjectCleanupFails() throws Exception {
    AppUserService users = mock(AppUserService.class);
    ObjectStorageService storage = mock(ObjectStorageService.class);
    var controller = new AuthController(users, mock(AuthenticationManager.class), mock(SecurityContextRepository.class),
        mock(EmailVerificationService.class), mock(AdminAuditService.class), storage);
    when(users.updateAvatar("reader", null)).thenReturn("old-avatar.png");
    when(users.profile("reader")).thenReturn(Map.of("avatarUrl", ""));
    doThrow(new IOException("test storage unavailable")).when(storage).delete("old-avatar.png");
    assertEquals("", controller.deleteAvatar(new TestingAuthenticationToken("reader", "")).get("avatarUrl"));
    verify(storage).delete("old-avatar.png");
  }
}
