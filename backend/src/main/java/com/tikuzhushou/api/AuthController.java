package com.tikuzhushou.api;

import com.tikuzhushou.identity.AppUserService;
import com.tikuzhushou.identity.EmailVerificationService;
import com.tikuzhushou.audit.AdminAuditService;
import com.tikuzhushou.document.ObjectStorageService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);
  private final AppUserService users;
  private final AuthenticationManager authenticationManager;
  private final SecurityContextRepository securityContexts;
  private final EmailVerificationService emailCodes;
  private final AdminAuditService audit;
  private final ObjectStorageService storage;
  public AuthController(AppUserService users, AuthenticationManager authenticationManager, SecurityContextRepository securityContexts,
      EmailVerificationService emailCodes, AdminAuditService audit, ObjectStorageService storage) { this.users = users; this.authenticationManager = authenticationManager; this.securityContexts = securityContexts; this.emailCodes = emailCodes; this.audit = audit; this.storage = storage; }

  @PostMapping("/login")
  Map<String, Object> login(@RequestBody Login request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
    try {
      Authentication authenticated = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
      Map<String, Object> profile = establish(authenticated, servletRequest, servletResponse);
      audit.recordSecurity(AdminAuditService.LOGIN_SUCCESS, authenticated.getName(), "SUCCESS", Map.of("mode", "PASSWORD"));
      return profile;
    } catch (AuthenticationException error) {
      audit.recordSecurity(AdminAuditService.LOGIN_FAILURE, "anonymous", "FAILURE", Map.of("mode", "PASSWORD", "reason", "invalid_credentials"));
      throw error;
    }
  }

  @PostMapping("/email-codes")
  Map<String, Object> emailCode(@RequestBody EmailCodeRequest request) {
    try {
      if (!EmailVerificationService.REGISTER.equals(request.purpose()) && !EmailVerificationService.LOGIN.equals(request.purpose())) throw new IllegalArgumentException("不支持的验证码用途");
      Map<String, Object> issued = emailCodes.issue(request.email(), request.purpose()); audit.recordSecurity(AdminAuditService.EMAIL_CODE_REQUEST, "anonymous", "SUCCESS", Map.of("purpose", request.purpose())); return issued;
    }
    catch (RuntimeException error) { audit.recordSecurity(AdminAuditService.EMAIL_CODE_REQUEST, "anonymous", "FAILURE", Map.of("purpose", request.purpose(), "reason", error.getMessage())); throw error; }
  }

  @PostMapping("/profile/email-code")
  Map<String, Object> emailChangeCode(@RequestBody EmailChangeCodeRequest request, Authentication authentication) {
    String target = EmailVerificationService.normalizedEmail(request.email());
    String current = Objects.toString(users.profile(authentication.getName()).get("email"), "");
    if (target.equalsIgnoreCase(current)) throw new IllegalArgumentException("新邮箱与当前邮箱相同");
    users.assertEmailAvailable(authentication.getName(), target);
    return emailCodes.issue(target, EmailVerificationService.EMAIL_CHANGE);
  }

  @PutMapping("/profile/email")
  Map<String, Object> changeEmail(@RequestBody EmailChangeRequest request, Authentication authentication,
      HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
    String email = EmailVerificationService.normalizedEmail(request.email());
    users.assertEmailAvailable(authentication.getName(), email);
    emailCodes.verify(email, EmailVerificationService.EMAIL_CHANGE, request.code());
    users.updateEmail(authentication.getName(), email);
    return refreshSession(authentication.getName(), servletRequest, servletResponse);
  }

  @PutMapping("/profile/phone")
  Map<String, Object> changePhone(@RequestBody PhoneChangeRequest request, Authentication authentication) {
    return users.updatePhone(authentication.getName(), request.currentPassword(), request.phoneNumber());
  }

  @PutMapping("/profile/password")
  Map<String, Object> changePassword(@RequestBody PasswordChangeRequest request, Authentication authentication,
      HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
    users.updatePassword(authentication.getName(), request.currentPassword(), request.newPassword());
    return refreshSession(authentication.getName(), servletRequest, servletResponse);
  }

  @PutMapping("/profile/settings")
  Map<String, Object> updateSettings(@RequestBody ProfileSettingsRequest request, Authentication authentication) {
    return users.updateProfileSettings(authentication.getName(), request.assistantEffort(), request.assistantWebSearch());
  }

  @PutMapping("/profile/display-name")
  Map<String, Object> updateDisplayName(@RequestBody DisplayNameRequest request, Authentication authentication) {
    return users.updateDisplayName(authentication.getName(), request.displayName());
  }

  @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Map<String, Object> uploadAvatar(@RequestParam("file") MultipartFile file, Authentication authentication) throws Exception {
    byte[] image = normalizeAvatar(file);
    String objectKey = "avatars/" + users.currentId(authentication.getName()) + "/" + UUID.randomUUID() + ".png";
    String stored;
    try (InputStream input = new ByteArrayInputStream(image)) { stored = storage.put(objectKey, input, image.length, MediaType.IMAGE_PNG_VALUE); }
    String previous;
    try { previous = users.updateAvatar(authentication.getName(), stored); }
    catch (RuntimeException error) { try { storage.delete(stored); } catch (Exception ignored) { } throw error; }
    if (previous != null && !previous.isBlank()) try { storage.delete(previous); } catch (Exception ignored) { }
    return users.profile(authentication.getName());
  }

  @DeleteMapping("/avatar")
  Map<String, Object> deleteAvatar(Authentication authentication) throws Exception {
    String previous = users.updateAvatar(authentication.getName(), null);
    if (previous != null && !previous.isBlank()) {
      try { storage.delete(previous); }
      catch (Exception error) { log.warn("Avatar was removed from the profile but storage cleanup failed", error); }
    }
    return users.profile(authentication.getName());
  }

  @GetMapping("/avatar")
  ResponseEntity<byte[]> avatar(Authentication authentication) throws Exception {
    String stored = users.avatarObjectKey(authentication.getName());
    if (stored == null || stored.isBlank()) return ResponseEntity.notFound().build();
    try (InputStream image = storage.open(stored)) {
      return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
          .header("X-Content-Type-Options", "nosniff").body(image.readAllBytes());
    }
  }

  @PostMapping("/login/email-code")
  Map<String, Object> emailCodeLogin(@RequestBody EmailCodeLogin request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
    try { emailCodes.verify(request.email(), EmailVerificationService.LOGIN, request.code()); UserDetails user = users.loadUserByEmail(request.email()); if (!user.isEnabled()) throw new IllegalStateException("ACCOUNT_DISABLED：该账号已被停用"); Authentication authenticated = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()); Map<String, Object> profile = establish(authenticated, servletRequest, servletResponse); audit.recordSecurity(AdminAuditService.LOGIN_EMAIL_CODE, authenticated.getName(), "SUCCESS", Map.of("mode", "EMAIL_CODE")); return profile; }
    catch (RuntimeException error) { audit.recordSecurity(AdminAuditService.LOGIN_FAILURE, "anonymous", "FAILURE", Map.of("mode", "EMAIL_CODE", "reason", error.getMessage())); throw error; }
  }

  private Map<String, Object> establish(Authentication authenticated, HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
    // An explicit account switch must never retain the previous account's HTTP session.
    // Create and populate the replacement session explicitly: this avoids losing the
    // authentication cookie when a controller logs in over an already-authenticated session.
    if (servletRequest.getSession(false) != null) servletRequest.getSession(false).invalidate();
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authenticated);
    SecurityContextHolder.setContext(context);
    HttpSession freshSession = servletRequest.getSession(true);
    freshSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    securityContexts.saveContext(context, servletRequest, servletResponse);
    Map<String, Object> profile = users.profile(authenticated.getName());
    freshSession.setAttribute(com.tikuzhushou.config.AccountSessionValidationFilter.ACCOUNT_ID, profile.get("id"));
    freshSession.setAttribute(com.tikuzhushou.config.AccountSessionValidationFilter.SESSION_REVISION, profile.get("sessionRevision"));
    return profile;
  }

  @PostMapping("/logout")
  Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
    audit.recordSecurity(AdminAuditService.LOGOUT, Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication()).map(Authentication::getName).orElse("anonymous"), "SUCCESS", Map.of());
    if (request.getSession(false) != null) request.getSession(false).invalidate();
    SecurityContextHolder.clearContext();
    jakarta.servlet.http.Cookie expired = new jakarta.servlet.http.Cookie("JSESSIONID", "");
    expired.setPath(request.getContextPath().isBlank() ? "/" : request.getContextPath());
    expired.setMaxAge(0); expired.setHttpOnly(true); response.addCookie(expired);
    return Map.of("status", "LOGGED_OUT");
  }

  @PostMapping("/register")
  Map<String, Object> register(@RequestBody Registration request) {
    if (request.email() != null && !request.email().isBlank()) {
      emailCodes.verify(request.email(), EmailVerificationService.REGISTER, request.code());
      users.registerEmail(request.username(), request.email(), request.password());
      return Map.of("status", "REGISTERED", "message", "邮箱已验证，账号已创建，请登录");
    }
    users.register(request.username(), request.password());
    return Map.of("status", "REGISTERED_LEGACY", "message", "账号已创建，请登录");
  }

  @GetMapping("/me")
  Map<String, Object> me(Authentication authentication) { return users.profile(authentication.getName()); }

  @GetMapping("/profile/summary")
  Map<String, Object> profileSummary(Authentication authentication) { return users.personalSummary(authentication.getName()); }

  @PutMapping("/profile")
  Map<String, Object> updateProfile(@RequestBody ProfileUpdate request, Authentication authentication,
      HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
    Map<String, Object> updated = users.updateUsername(authentication.getName(), request.username());
    return refreshSession(updated.get("username").toString(), servletRequest, servletResponse);
  }

  private Map<String, Object> refreshSession(String username, HttpServletRequest request, HttpServletResponse response) {
    UserDetails updated = users.loadUserByUsername(username);
    Authentication refreshed = UsernamePasswordAuthenticationToken.authenticated(updated, null, updated.getAuthorities());
    return establish(refreshed, request, response);
  }

  private byte[] normalizeAvatar(MultipartFile file) {
    if (file == null || file.isEmpty() || file.getSize() > 2 * 1024 * 1024) throw new IllegalArgumentException("头像不能为空且不能超过 2 MB");
    String type = Objects.toString(file.getContentType(), "").toLowerCase();
    if (!MediaType.IMAGE_PNG_VALUE.equals(type) && !MediaType.IMAGE_JPEG_VALUE.equals(type)) throw new IllegalArgumentException("头像只支持 PNG 或 JPEG 图片");
    try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
      if (imageInput == null) throw new IllegalArgumentException("无法读取该图片，请使用 PNG 或 JPEG 格式");
      Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
      if (!readers.hasNext()) throw new IllegalArgumentException("无法读取该图片，请使用 PNG 或 JPEG 格式");
      ImageReader reader = readers.next();
      try {
        reader.setInput(imageInput, true, true);
        String format = reader.getFormatName().toLowerCase();
        if (!format.equals("png") && !format.equals("jpeg")) throw new IllegalArgumentException("头像只支持 PNG 或 JPEG 图片");
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 12_000_000L) throw new IllegalArgumentException("图片尺寸过大，请选择 4096 × 4096 以内的图片");
        BufferedImage decoded = reader.read(0);
        if (decoded == null) throw new IllegalArgumentException("图片内容无法解码");
        int side = Math.min(width, height), x = (width - side) / 2, y = (height - side) / 2;
        BufferedImage square = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = square.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 256, 256);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(decoded, 0, 0, 256, 256, x, y, x + side, y + side, null); graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(square, "png", output);
        return output.toByteArray();
      } finally { reader.dispose(); }
    } catch (IOException error) { throw new IllegalArgumentException("图片文件损坏或无法读取，请重新选择 PNG 或 JPEG 图片"); }
  }

  public record Registration(String username, String email, String password, String code) {}
  public record Login(String username, String password) {}
  public record ProfileUpdate(String username) {}
  public record EmailChangeCodeRequest(String email) {}
  public record EmailChangeRequest(String email, String code) {}
  public record PhoneChangeRequest(String phoneNumber, String currentPassword) {}
  public record PasswordChangeRequest(String currentPassword, String newPassword) {}
  public record ProfileSettingsRequest(String assistantEffort, Boolean assistantWebSearch) {}
  public record DisplayNameRequest(String displayName) {}
  public record EmailCodeRequest(String email, String purpose) {}
  public record EmailCodeLogin(String email, String code) {}
}
