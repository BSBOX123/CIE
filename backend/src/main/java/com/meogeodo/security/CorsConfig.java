package com.meogeodo.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 브라우저 교차 출처 요청 허용 설정.
 *
 * <p>{@code SecurityConfig} 의 {@code .cors(...)} 는 이 빈이 있어야 동작한다. 없으면
 * 조용히 아무 일도 하지 않아, 프론트가 붙여 보기 전까지 문제가 드러나지 않는다.
 * Swagger UI 는 같은 출처라 이 설정과 무관하게 잘 돌아간다 — 그래서 더 늦게 발견된다.
 */
@Configuration
public class CorsConfig {

  private final List<String> allowedOrigins;

  /**
   * 허용할 프론트 출처. 쉼표로 여러 개.
   *
   * <p>출처는 <b>스킴·호스트·포트가 정확히</b> 일치해야 한다. {@code https://mukeodo.site} 를
   * 넣었다고 {@code https://www.mukeodo.site} 나 {@code http://} 가 함께 허용되지는 않는다.
   * 프론트를 로컬에서 띄워 붙일 때는 그 주소를 함께 넣는다.
   *
   * <pre>CORS_ALLOWED_ORIGINS=https://mukeodo.site,http://localhost:5173</pre>
   */
  public CorsConfig(
      @Value("${meogeodo.security.allowed-origins:https://mukeodo.site}")
          List<String> allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(allowedOrigins);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    // 토큰을 Authorization 헤더로 받으므로 요청 헤더는 제한하지 않는다.
    config.setAllowedHeaders(List.of("*"));
    // 인증을 쿠키가 아니라 Bearer 토큰으로 한다. 자격증명 허용은 필요 없고,
    // 켜면 출처 검사가 엄격해지는 대신 CSRF 표면만 넓어진다.
    config.setAllowCredentials(false);
    // 201 Created 의 Location 등 기본 노출 대상이 아닌 헤더를 프론트가 읽게 한다.
    config.setExposedHeaders(List.of("Location"));
    // 프리플라이트(OPTIONS) 결과를 1시간 캐시한다.
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }
}
