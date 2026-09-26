package com.tikuzhushou.config;

import com.tikuzhushou.identity.AppUserService;
import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects a browser session when the backing account was replaced, renamed, disabled or removed. */
@Component
public class AccountSessionValidationFilter extends OncePerRequestFilter {
  public static final String ACCOUNT_ID = "TIKU_ACCOUNT_ID";
  public static final String SESSION_REVISION = "TIKU_SESSION_REVISION";
  private final AppUserService users;

  public AccountSessionValidationFilter(AppUserService users) { this.users = users; }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/api/") || path.equals("/api/health") || path.equals("/api/auth/login")
        || path.equals("/api/auth/register") || path.equals("/api/auth/email-codes") || path.equals("/api/auth/login/email-code");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    HttpSession session = request.getSession(false);
    if (authentication != null && authentication.isAuthenticated() && session != null) {
      String accountId = String.valueOf(session.getAttribute(ACCOUNT_ID));
      Object revision = session.getAttribute(SESSION_REVISION);
      if (!users.isCurrentSession(authentication.getName(), accountId, revision)) {
        session.invalidate();
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"statusCode\":\"SESSION_EXPIRED\",\"message\":\"账号资料已更新或会话已失效，请重新登录\"}");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
