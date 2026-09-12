package com.meogeodo.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** 인증·인가 설정 (SPEC 9.1, D5: 자체 ID/PW + JWT). */
@Configuration
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtFilter;

  public SecurityConfig(JwtService jwtService) {
    // 빈으로 주입받지 않고 직접 만든다. 필터가 서블릿 컨테이너 체인에
    // 자동 등록되는 것을 막기 위함이다 (JwtAuthenticationFilter 주석 참조).
    this.jwtFilter = new JwtAuthenticationFilter(jwtService);
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        // 토큰 기반이라 세션도 CSRF 토큰도 쓰지 않는다.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .cors(Customizer.withDefaults())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/**").permitAll()
            // 식당·지역음식 조회는 비로그인도 허용한다. 판정 없이 목록만 보이며,
            // 로그인하면 같은 경로가 사용자 기준 판정을 함께 돌려준다.
            .requestMatchers("/api/restaurants/**", "/api/foods/**").permitAll()
            .requestMatchers("/api/vocabulary", "/api/reports/options").permitAll()
            .requestMatchers("/actuator/health").permitAll()
            // API 문서. 프론트에 배포 주소와 함께 넘기는 용도라 열어 둔다.
            // 닫으려면 springdoc.swagger-ui.enabled=false 로 끄는 편이 낫다.
            .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
            .permitAll()
            // 오류 포워딩 경로를 막으면 컨트롤러의 실제 예외가 401 로 덮여
            // 원인을 알 수 없게 된다.
            .requestMatchers("/error").permitAll()
            .anyRequest().authenticated())
        .exceptionHandling(e ->
            e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
