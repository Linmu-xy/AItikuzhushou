package com.tikuzhushou.config;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
@Configuration public class SecurityConfig {
  @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
  @Bean SecurityContextRepository securityContextRepository(){ return new HttpSessionSecurityContextRepository(); }
  @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception { return configuration.getAuthenticationManager(); }
  @Bean SecurityFilterChain filterChain(HttpSecurity http, SecurityContextRepository repository, AccountSessionValidationFilter sessions) throws Exception{return http.csrf(c->c.disable()).cors(Customizer.withDefaults()).securityContext(c->c.securityContextRepository(repository).requireExplicitSave(false)).exceptionHandling(c->c.authenticationEntryPoint((request,response,error)->{response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"statusCode\":\"AUTHENTICATION_REQUIRED\",\"message\":\"请先登录后继续操作\"}");})).httpBasic(c->c.authenticationEntryPoint((request,response,error)->{response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"statusCode\":\"AUTHENTICATION_REQUIRED\",\"message\":\"请先登录后继续操作\"}");})).authorizeHttpRequests(a->a.requestMatchers("/api/health","/api/auth/register","/api/auth/login","/api/auth/email-codes","/api/auth/login/email-code","/api/knowledge-bases/shared/**","/swagger-ui/**","/v3/api-docs/**").permitAll().requestMatchers("/api/admin/**").hasRole("ADMIN").anyRequest().authenticated()).addFilterAfter(sessions, AnonymousAuthenticationFilter.class).build();}
}
