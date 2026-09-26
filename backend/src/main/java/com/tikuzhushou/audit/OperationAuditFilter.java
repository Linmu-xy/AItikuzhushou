package com.tikuzhushou.audit;

import com.tikuzhushou.config.TraceFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps failure and state-changing request evidence without allowing polling,
 * log reading and other successful GET traffic to drown out real operations.
 */
@Component
public class OperationAuditFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(OperationAuditFilter.class);
  private final JdbcTemplate jdbc;

  public OperationAuditFilter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/") || "/api/health".equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    StatusResponse wrapped = new StatusResponse(response);
    try {
      chain.doFilter(request, wrapped);
    } finally {
      String actor = Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
          .map(a -> a.getName()).orElse("anonymous");
      int status = wrapped.status;
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      if (status >= 400) {
        log.warn("http {} {} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(), actor, status, elapsedMs);
      }
      boolean stateChanging = !"GET".equalsIgnoreCase(request.getMethod()) && !"HEAD".equalsIgnoreCase(request.getMethod()) && !"OPTIONS".equalsIgnoreCase(request.getMethod());
      // Authentication submissions are material; routine session/profile reads are not.
      boolean securityEndpoint = request.getRequestURI().startsWith("/api/auth/")
          && !"GET".equalsIgnoreCase(request.getMethod());
      if (stateChanging || status >= 400 || securityEndpoint) {
        try {
          jdbc.update("insert into operation_logs(id,actor,method,path,status,elapsed_ms,remote_ip,request_id,created_at) values(?,?,?,?,?,?,?,?,?)",
              UUID.randomUUID(), actor, request.getMethod(), request.getRequestURI(), status, elapsedMs,
              request.getRemoteAddr(), org.slf4j.MDC.get(TraceFilter.MDC_KEY), Timestamp.from(Instant.now()));
        } catch (Exception ignored) {
          log.error("failed to persist operation audit for {} {}", request.getMethod(), request.getRequestURI());
        }
      }
    }
  }

  @Scheduled(cron = "0 10 3 * * *")
  public void retainThirtyDays() {
    int removed = jdbc.update("delete from operation_logs where created_at < ?",
        Timestamp.from(Instant.now().minusSeconds(30L * 24 * 3600)));
    log.info("operation log retention removed {} rows older than 30 days", removed);
  }

  private static final class StatusResponse extends HttpServletResponseWrapper {
    int status = 200;
    StatusResponse(HttpServletResponse response) {
      super(response);
    }
    @Override
    public void setStatus(int sc) {
      status = sc;
      super.setStatus(sc);
    }
    @Override
    public void sendError(int sc) throws IOException {
      status = sc;
      super.sendError(sc);
    }
    @Override
    public void sendError(int sc, String msg) throws IOException {
      status = sc;
      super.sendError(sc, msg);
    }
    @Override
    public void sendRedirect(String location) throws IOException {
      status = 302;
      super.sendRedirect(location);
    }
  }
}
