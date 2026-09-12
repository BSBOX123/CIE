package com.meogeodo.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Swagger 문서가 실제로 만들어지는지 검증하고, 원하면 파일로 내보낸다.
 *
 * <p>내보내기:
 *
 * <pre>EXPORT_OPENAPI=1 ./gradlew test --tests '*OpenApiDocsTest'</pre>
 *
 * <p>→ {@code docs/openapi.json}. 서버를 띄울 수 없는 사람(프론트)에게 넘기는 용도다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocsTest {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  /** SPEC 9 장에 적힌 엔드포인트. 하나라도 문서에서 빠지면 프론트가 모르게 된다. */
  private static final List<String> EXPECTED_PATHS =
      List.of(
          "/api/auth/signup",
          "/api/auth/login",
          "/api/me/profile",
          "/api/restaurants",
          "/api/restaurants/{id}",
          "/api/order-cards",
          "/api/order-cards/{id}",
          "/api/reports",
          "/api/restaurants/{id}/reports",
          "/api/me/reports",
          "/api/me/reports/{id}",
          "/api/reports/options");

  private JsonNode fetchDoc() throws Exception {
    String body =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body);
  }

  @Test
  @DisplayName("문서가 생성되고 모든 엔드포인트가 실려 있다")
  void documentCoversEveryEndpoint() throws Exception {
    JsonNode doc = fetchDoc();

    assertThat(doc.at("/info/title").asText()).isEqualTo("먹어도 돼? API");
    JsonNode paths = doc.get("paths");
    assertThat(paths).isNotNull();
    for (String path : EXPECTED_PATHS) {
      assertThat(paths.has(path)).as("문서에 %s 가 없다", path).isTrue();
    }
  }

  @Test
  @DisplayName("문서와 UI 는 토큰 없이 열린다")
  void docsAreOpenWithoutToken() throws Exception {
    // 프론트가 배포 주소만 받고도 열어볼 수 있어야 한다.
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("JWT 인증 방식이 문서에 정의되어 있다")
  void bearerSchemeIsDeclared() throws Exception {
    JsonNode scheme = fetchDoc().at("/components/securitySchemes/bearerAuth");
    assertThat(scheme.isMissingNode()).isFalse();
    assertThat(scheme.get("scheme").asText()).isEqualTo("bearer");
    assertThat(scheme.get("bearerFormat").asText()).isEqualTo("JWT");
  }

  /**
   * {@code @AuthenticationPrincipal Long userId} 는 JWT 에서 꺼내는 값이지 클라이언트가
   * 보내는 파라미터가 아니다. 문서에 새면 프론트가 {@code ?userId=} 를 붙여 보내게 된다.
   */
  @Test
  @DisplayName("서버 내부 값(userId)이 요청 파라미터로 새지 않는다")
  void authenticationPrincipalIsNotExposed() throws Exception {
    List<String> leaked = new ArrayList<>();
    JsonNode paths = fetchDoc().get("paths");
    paths.fields()
        .forEachRemaining(
            pathEntry ->
                pathEntry
                    .getValue()
                    .fields()
                    .forEachRemaining(
                        opEntry -> {
                          JsonNode params = opEntry.getValue().get("parameters");
                          if (params == null) {
                            return;
                          }
                          for (JsonNode p : params) {
                            if ("userId".equals(p.path("name").asText())) {
                              leaked.add(
                                  opEntry.getKey().toUpperCase() + " " + pathEntry.getKey());
                            }
                          }
                        }));
    assertThat(leaked).isEmpty();
  }

  @Test
  @DisplayName("EXPORT_OPENAPI=1 이면 docs/openapi.json 으로 내보낸다")
  void exportsWhenAsked() throws Exception {
    if (!"1".equals(System.getenv("EXPORT_OPENAPI"))) {
      return; // 평소에는 파일을 건드리지 않는다.
    }
    // 작업 디렉터리는 backend/ 다. 문서는 프로젝트 루트의 docs/ 에 둔다.
    Path out = Path.of("..", "docs", "openapi.json");
    Files.createDirectories(out.getParent());
    Files.writeString(out, json.writerWithDefaultPrettyPrinter().writeValueAsString(fetchDoc()));
    assertThat(Files.size(out)).isGreaterThan(1000);
  }
}
