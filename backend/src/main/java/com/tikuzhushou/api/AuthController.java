package com.tikuzhushou.api;

import com.tikuzhushou.identity.AppUserService;
import com.tikuzhushou.identity.EmailVerificationService;
import com.tikuzhushou.audit.AdminAuditService;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AppUserService users;
  private final AuthenticationManager authenticationManager;
  private final SecurityContextRepository securityContexts;
  private final EmailVerificationService emailCodes;
  private final AdminAuditService audit;
  public AuthController(AppUserService users, AuthenticationManager authenticationManager, SecurityContextRepository securityContexts,
      EmailVerificationService emailCodes, AdminAuditService audit) { this.users = users; this.authenticationManager = authenticationManager; this.securityContexts = securityContexts; this.emailCodes = emailCodes; this.audit = audit; }

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
    try { Map<String, Object> issued = emailCodes.issue(request.email(), request.purpose()); audit.recordSecurity(AdminAuditService.EMAIL_CODE_REQUEST, "anonymous", "SUCCESS", Map.of("purpose", request.purpose())); return issued; }
    catch (RuntimeException error) { audit.recordSecurity(AdminAuditService.EMAIL_CODE_REQUEST, "anonymous", "FAILURE", Map.of("purpose", request.purpose(), "reason", error.getMessage())); throw error; }
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
    users.updateUsername(authentication.getName(), request.username());
    UserDetails updated = users.loadUserByUsername(request.username());
    Authentication refreshed = UsernamePasswordAuthenticationToken.authenticated(updated, null, updated.getAuthorities());
    return establish(refreshed, servletRequest, servletResponse);
  }

  public record Registration(String username, String email, String password, String code) {}
  public record Login(String username, String password) {}
  public record ProfileUpdate(String username) {}
  public record EmailCodeRequest(String email, String purpose) {}
  public record EmailCodeLogin(String email, String code) {}
}
