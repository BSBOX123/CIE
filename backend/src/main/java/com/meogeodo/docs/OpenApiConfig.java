package com.meogeodo.docs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayList;
import java.util.List;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * Swagger / OpenAPI 3 문서 설정.
 *
 * <ul>
 *   <li>문서(JSON): {@code /v3/api-docs}
 *   <li>Swagger UI: {@code /swagger-ui.html}
 * </ul>
 *
 * <p>두 경로 모두 인증 없이 열려 있다 (SecurityConfig). 프론트에 배포 주소와
 * 함께 넘기는 것이 이 문서의 용도다.
 */
@Configuration
public class OpenApiConfig {

  static {
    // 컨트롤러의 @AuthenticationPrincipal Long userId 는 JWT 에서 꺼내는 값이지
    // 클라이언트가 보내는 파라미터가 아니다. 빼지 않으면 문서에 userId 라는
    // 없는 쿼리 파라미터가 생겨 프론트가 그대로 붙여 보내게 된다.
    SpringDocUtils.getConfig().addAnnotationsToIgnore(AuthenticationPrincipal.class);
  }

  /** 배포 주소. 비어 있으면 요청 URL 에서 추론한다. */
  @Value("${meogeodo.openapi.server-url:}")
  private String serverUrl;

  @Bean
  public OpenAPI meogeodoOpenApi() {
    Components components =
        new Components()
            .addSecuritySchemes(
                "bearerAuth",
                new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description(
                        """
                        로그인(`POST /api/auth/login`) 또는 회원가입 응답의 `accessToken` 을 넣는다.
                        Swagger UI 오른쪽 위 **Authorize** 에 토큰 값만 붙여 넣으면 된다 \
                        (`Bearer ` 접두사는 UI 가 붙인다)."""));

    OpenAPI api = new OpenAPI().components(components).info(info());

    List<Server> servers = new ArrayList<>();
    if (!serverUrl.isBlank()) {
      servers.add(new Server().url(serverUrl).description("배포"));
    }
    servers.add(new Server().url("http://localhost:8080").description("로컬"));
    api.servers(servers);
    return api;
  }

  private Info info() {
    return new Info()
        .title("먹어도 돼? API")
        .version("0.1.0")
        .contact(new Contact().name("먹어도 돼? 백엔드"))
        .description(
            """
            지병이 있는 사람이 관광지에서 외식할 때, 그 메뉴를 먹어도 되는지 /
            무엇을 빼고 먹어야 하는지 알려주는 서비스의 백엔드 API.

            ## 인증

            자체 ID/PW + JWT. 로그인 응답의 `accessToken` 을 `Authorization: Bearer <토큰>` 으로 보낸다.

            - **필수**: `/api/me/**`, `/api/order-cards/**`, `POST /api/reports`
            - **선택**: `/api/restaurants/**`, `/api/foods/**` — 토큰이 없으면 목록만, 있으면 **사용자 기준 판정**이 함께 온다.
              이때 응답의 `personalized` 로 어느 쪽인지 알 수 있다.
            - **불필요**: `/api/auth/**`, `GET /api/reports/options`

            ## 위치와 지역 선택

            식당·지역 음식 API 는 좌표(`lat`, `lng`)로만 동작한다. 서버는 사용자 위치를 모른다.

            - **내 위치**: 브라우저 `navigator.geolocation` 으로 얻은 좌표를 보낸다
            - **지역 직접 선택** (여행 전 미리 찾아보기, 위치 권한 거부): 저장소의
              [`docs/regions.json`](https://github.com/BSBOX123/CIE/blob/main/docs/regions.json)
              에서 고른 시도·시군구의 대표 좌표를 보낸다. 시도만 고르면 그 시도의 `default`
              시군구 좌표를 쓴다. 검색 반경은 `radius=5000` 을 권한다
            - 응답의 거리·도보 시간은 **보낸 좌표 기준**이다. 직접 고른 지역에서는 사용자와의
              거리가 아니므로 숨기거나 "중심에서 n km" 로 표시할 것
            - 식당은 관광공사에서 실시간으로 받는다. 못 받으면 `503 TOUR_API_UNAVAILABLE`
              (빈 목록이 아니다). 식당 상세에서 메뉴만 못 받으면 200 + `menusUnavailable=true`

            ## 판정값 (`seal`)

            색만으로 전달하지 않는다. 기호와 라벨을 항상 함께 노출할 것.

            | verdict | symbol | label |
            |---|---|---|
            | `RED` | ✕ | 알레르기 주의 |
            | `INK` | △ | 조절하면 가능 |
            | `OK` | ○ | 먹어도 돼요! |

            메뉴 판정은 **비관적**(하나라도 걸리면 위험), 식당 판정은 **낙관적**(안전한 메뉴가
            하나라도 있으면 갈 만함)이다. 방향이 반대인 것은 의도된 설계다.

            `seal` 이 `null` 인 경우가 있다. 아직 **분석되지 않은(미태깅) 메뉴/식당**이라는 뜻이며,
            **안전하다는 뜻이 아니다.** 프론트는 이 경우 판정 배지를 그리지 말고
            "분석 전" 으로 표시해야 한다.

            ## 오류 형식

            모든 오류는 `{ "code": "...", "message": "..." }` 형태다.

            ## 주의

            이 API 의 판정은 참고용이며 의학적 조언이 아니다. 응답의 `disclaimer` 문구를
            화면에 그대로 노출할 것.
            """);
  }
}
