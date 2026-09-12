package com.meogeodo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meogeodo.user.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 회원가입·로그인·프로필 API 테스트 (MVP 5·6번). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private AppUserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager em;

  private Map<String, Object> signupBody() {
    return Map.of(
        "loginId", "meog0112",
        "password", "secret123",
        "name", "김영수",
        "gender", "남성",
        "birthYear", 1958,
        "bloodType", "A형",
        "diseases", List.of("당뇨", "고혈압"),
        "allergies", List.of("새우", "고등어"),
        "medications", List.of("혈압약"));
  }

  private String signupAndGetToken() throws Exception {
    String body =
        mvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(signupBody())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("accessToken").asText();
  }

  @Nested
  @DisplayName("회원가입 (MVP 6번)")
  class Signup {

    @Test
    @DisplayName("가입하면 토큰을 바로 돌려준다")
    void signupReturnsToken() throws Exception {
      mvc.perform(post("/api/auth/signup")
              .contentType(MediaType.APPLICATION_JSON)
              .content(json.writeValueAsString(signupBody())))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.accessToken").isNotEmpty())
          .andExpect(jsonPath("$.tokenType").value("Bearer"));
      assertThat(users.existsByLoginId("meog0112")).isTrue();
    }

    @Test
    @DisplayName("질환에서 주의성분이 자동으로 따라온다 (SPEC 2.3)")
    void autoCares() throws Exception {
      String token = signupAndGetToken();
      mvc.perform(get("/api/me/profile").header("Authorization", "Bearer " + token))
          // 당뇨(당류·정제 탄수화물) + 고혈압(나트륨·포화지방)
          .andExpect(jsonPath("$.cares.length()").value(4))
          .andExpect(jsonPath("$.cares[?(@.value=='나트륨')].auto").value(true));
    }

    @Test
    @DisplayName("어휘에 없는 값은 저장하지 않는다 — 철자가 다르면 판정이 조용히 실패한다")
    void rejectsUnknownVocabulary() throws Exception {
      var body = new java.util.HashMap<>(signupBody());
      body.put("diseases", List.of("당뇨병"));   // 정본은 '당뇨'
      body.put("allergies", List.of("새우", "존재하지않는알레르겐"));

      mvc.perform(post("/api/auth/signup")
              .contentType(MediaType.APPLICATION_JSON)
              .content(json.writeValueAsString(body)))
          .andExpect(status().isCreated());

      var user = users.findByLoginId("meog0112").orElseThrow();
      assertThat(user.getDiseases()).isEmpty();
      assertThat(user.getAllergies()).containsExactly("새우");
    }

    @Test
    @DisplayName("아이디가 중복되면 409")
    void duplicateLoginId() throws Exception {
      signupAndGetToken();
      mvc.perform(post("/api/auth/signup")
              .contentType(MediaType.APPLICATION_JSON)
              .content(json.writeValueAsString(signupBody())))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("DUPLICATE_LOGIN_ID"));
    }

    @Test
    @DisplayName("비밀번호가 6자 미만이면 400 (SPEC 10.1 s1)")
    void shortPassword() throws Exception {
      var body = new java.util.HashMap<>(signupBody());
      body.put("password", "12345");
      mvc.perform(post("/api/auth/signup")
              .contentType(MediaType.APPLICATION_JSON)
              .content(json.writeValueAsString(body)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
  }

  @Nested
  @DisplayName("민감정보 저장 (SPEC 11.4)")
  class SensitiveStorage {

    @Test
    @DisplayName("질환·알레르기가 DB에 평문으로 남지 않는다")
    void encryptedAtRest() throws Exception {
      signupAndGetToken();
      // Hibernate 는 flush 를 미루므로 raw SQL 로 보려면 먼저 내보내야 한다.
      em.flush();

      List<String> storedDiseases =
          jdbc.queryForList("SELECT code FROM user_disease", String.class);
      List<String> storedAllergies =
          jdbc.queryForList("SELECT code FROM user_allergy", String.class);

      assertThat(storedDiseases).isNotEmpty().doesNotContain("당뇨", "고혈압");
      assertThat(storedAllergies).isNotEmpty().doesNotContain("새우", "고등어");
    }

    @Test
    @DisplayName("암호화해도 읽을 때 원래 값으로 돌아온다")
    void roundTrips() throws Exception {
      signupAndGetToken();
      // 영속성 컨텍스트를 비워 실제로 DB에서 복호화해 읽는지 확인한다.
      em.flush();
      em.clear();
      var user = users.findByLoginId("meog0112").orElseThrow();
      assertThat(user.getDiseases()).containsExactlyInAnyOrder("당뇨", "고혈압");
      assertThat(user.getAllergies()).containsExactlyInAnyOrder("새우", "고등어");
      assertThat(user.getMedications()).containsExactly("혈압약");
    }

    @Test
    @DisplayName("비밀번호는 해시로 저장된다")
    void passwordIsHashed() throws Exception {
      signupAndGetToken();
      var user = users.findByLoginId("meog0112").orElseThrow();
      assertThat(user.getPasswordHash()).isNotEqualTo("secret123").startsWith("$2");
    }
  }

  @Nested
  @DisplayName("로그인 (MVP 5번)")
  class Login {

    @Test
    @DisplayName("올바른 자격증명이면 토큰을 준다")
    void loginSucceeds() throws Exception {
      signupAndGetToken();
      mvc.perform(post("/api/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"loginId\":\"meog0112\",\"password\":\"secret123\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("없는 아이디와 틀린 비밀번호를 같은 응답으로 처리한다")
    void doesNotLeakAccountExistence() throws Exception {
      signupAndGetToken();

      String wrongPassword =
          mvc.perform(post("/api/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"loginId\":\"meog0112\",\"password\":\"틀린비밀번호\"}"))
              .andExpect(status().isUnauthorized())
              .andReturn().getResponse().getContentAsString();

      String noSuchUser =
          mvc.perform(post("/api/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"loginId\":\"없는사람\",\"password\":\"secret123\"}"))
              .andExpect(status().isUnauthorized())
              .andReturn().getResponse().getContentAsString();

      // 응답이 다르면 어떤 아이디가 가입되어 있는지 알아낼 수 있다.
      assertThat(wrongPassword).isEqualTo(noSuchUser);
    }
  }

  @Nested
  @DisplayName("프로필 조회·수정")
  class Profile {

    @Test
    @DisplayName("토큰 없이는 401")
    void requiresToken() throws Exception {
      mvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("위조된 토큰은 401")
    void rejectsForgedToken() throws Exception {
      mvc.perform(get("/api/me/profile").header("Authorization", "Bearer 아무값"))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("판정 관련 응답에는 의료 면책이 붙는다 (SPEC 11.1)")
    void includesDisclaimer() throws Exception {
      String token = signupAndGetToken();
      mvc.perform(get("/api/me/profile").header("Authorization", "Bearer " + token))
          .andExpect(jsonPath("$.disclaimer").value(
              org.hamcrest.Matchers.containsString("의학적 조언이 아닙니다")));
    }

    @Test
    @DisplayName("복용약은 기본적으로 카드에 표시하지 않는다 (SPEC 11.4)")
    void medsHiddenByDefault() throws Exception {
      String token = signupAndGetToken();
      mvc.perform(get("/api/me/profile").header("Authorization", "Bearer " + token))
          .andExpect(jsonPath("$.showMedsOnCard").value(false));
    }

    @Test
    @DisplayName("질환을 바꾸면 자동 주의성분이 다시 계산된다")
    void diseaseChangeRecalculatesCares() throws Exception {
      String token = signupAndGetToken();

      mvc.perform(put("/api/me/profile")
              .header("Authorization", "Bearer " + token)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"diseases\":[\"만성콩팥병\"]}"))
          .andExpect(status().isOk())
          // 만성콩팥병 -> 나트륨·칼륨·단백질량
          .andExpect(jsonPath("$.cares.length()").value(3))
          .andExpect(jsonPath("$.cares[?(@.value=='칼륨')].auto").value(true));
    }

    @Test
    @DisplayName("보내지 않은 항목은 그대로 둔다")
    void partialUpdate() throws Exception {
      String token = signupAndGetToken();
      mvc.perform(put("/api/me/profile")
              .header("Authorization", "Bearer " + token)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"fontScaleIdx\":4}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.fontScaleIdx").value(4))
          .andExpect(jsonPath("$.name").value("김영수"))
          .andExpect(jsonPath("$.allergies.length()").value(2));
    }

    @Test
    @DisplayName("주의성분에 안내 문구가 함께 온다 (SPEC 2.2)")
    void caresIncludeNotes() throws Exception {
      String token = signupAndGetToken();
      mvc.perform(get("/api/me/profile").header("Authorization", "Bearer " + token))
          .andExpect(jsonPath("$.cares[?(@.value=='나트륨')].note").value(
              org.hamcrest.Matchers.hasItem(
                  org.hamcrest.Matchers.containsString("국물"))));
    }
  }
}
