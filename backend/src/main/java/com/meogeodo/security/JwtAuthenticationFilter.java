package com.meogeodo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer ...} 를 읽어 인증 정보를 채운다.
 *
 * <p>토큰이 없거나 잘못돼도 여기서 거부하지 않는다. 비로그인 사용자도 식당을
 * 둘러볼 수 있어야 하고(판정 없이), 접근 제어는 SecurityConfig 가 경로별로
 * 결정한다.
 *
 * <p><b>스프링 빈으로 등록하지 않는다.</b> {@code @Component} 를 붙이면 스프링
 * 부트가 이 필터를 서블릿 컨테이너 체인에도 자동 등록해 두 번 꽂힌다. 그러면
 * ① 서블릿 체인에서 먼저 돌아 인증을 채우고 ② 시큐리티의
 * {@code SecurityContextHolderFilter} 가 그것을 비우며 ③ 시큐리티 체인 안의
 * 두 번째 실행은 {@code OncePerRequestFilter} 가 "이미 실행됨"으로 건너뛴다.
 * 결과는 항상 401이다. {@link SecurityConfig} 가 직접 생성해 체인에 넣는다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String PREFIX = "Bearer ";

  private final JwtService jwtService;

  public JwtAuthenticationFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain chain)
      throws ServletException, IOException {

    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith(PREFIX)) {
      Long userId = jwtService.resolveUserId(header.substring(PREFIX.length()).trim());
      if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
        var authentication =
            new UsernamePasswordAuthenticationToken(userId, null, List.of());
        authentication.setDetails(
            new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
    }
    chain.doFilter(request, response);
  }
}
