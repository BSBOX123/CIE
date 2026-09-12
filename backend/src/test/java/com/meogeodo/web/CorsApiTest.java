package com.meogeodo.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 프론트(https://mukeodo.site)에서 브라우저로 호출할 수 있는지.
 *
 * <p>CORS 설정이 빠져도 Swagger UI 와 서버 간 호출은 멀쩡히 돌아간다. 프론트가 붙여
 * 보기 전까지 드러나지 않는 종류의 고장이라 테스트로 못 박는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsApiTest {

  private static final String FRONTEND = "https://mukeodo.site";

  @Autowired private MockMvc mvc;

  @Test
  @DisplayName("프론트 출처의 프리플라이트를 통과시킨다")
  void allowsPreflightFromFrontend() throws Exception {
    mvc.perform(
            options("/api/order-cards")
                .header("Origin", FRONTEND)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND));
  }

  @Test
  @DisplayName("토큰이 필요한 경로도 프리플라이트는 인증 없이 통과한다")
  void preflightIsNotBlockedByAuth() throws Exception {
    // 프리플라이트에는 Authorization 헤더가 실리지 않는다. 여기서 401 이 나가면
    // 브라우저는 본 요청을 보내지도 않는다.
    mvc.perform(
            options("/api/me/profile")
                .header("Origin", FRONTEND)
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("실제 응답에도 허용 헤더가 붙는다")
  void actualResponseCarriesCorsHeader() throws Exception {
    mvc.perform(get("/api/reports/options").header("Origin", FRONTEND))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND));
  }

  @Test
  @DisplayName("허용하지 않은 출처는 막는다")
  void rejectsUnknownOrigin() throws Exception {
    mvc.perform(
            options("/api/order-cards")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("www 와 http 는 별개 출처다 — 자동으로 허용되지 않는다")
  void subdomainAndSchemeAreSeparateOrigins() throws Exception {
    // 배포 후 www 로 들어오는 경우가 있으면 CORS_ALLOWED_ORIGINS 에 따로 넣어야 한다.
    mvc.perform(
            options("/api/order-cards")
                .header("Origin", "https://www.mukeodo.site")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
    mvc.perform(
            options("/api/order-cards")
                .header("Origin", "http://mukeodo.site")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }
}
