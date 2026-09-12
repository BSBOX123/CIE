package com.meogeodo.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 운영 키로 기동되는지 확인한다.
 *
 * <p>키 길이나 Base64 형식이 잘못되면 기동 시점이 아니라 첫 저장에서 터진다.
 * 배포 전에 미리 확인하기 위한 테스트다. 환경변수가 있을 때만 돈다.
 *
 * <pre>
 *   set -a; source .env; set +a
 *   ./gradlew test --tests '*RealKeyBootTest'
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MEOGEODO_ENCRYPTION_KEY", matches = ".+")
class RealKeyBootTest {

  @DynamicPropertySource
  static void realKeys(DynamicPropertyRegistry registry) {
    registry.add("meogeodo.security.encryption-key",
        () -> System.getenv("MEOGEODO_ENCRYPTION_KEY"));
    registry.add("meogeodo.security.jwt-secret", () -> System.getenv("JWT_SECRET"));
  }

  @Autowired private JwtService jwtService;

  @Test
  @DisplayName("실제 키로 토큰을 발급하고 되읽는다")
  void jwtRoundTrip() {
    String token = jwtService.issue(42L, "meog0112");
    assertThat(jwtService.resolveUserId(token)).isEqualTo(42L);
    System.out.println("  [실측] JWT 발급·검증 정상, 만료 " + jwtService.accessTokenSeconds() + "초");
  }

  @Test
  @DisplayName("실제 키로 건강정보를 암호화하고 복호화한다")
  void encryptionRoundTrip() {
    SensitiveDataConverter converter = new SensitiveDataConverter();
    String encrypted = converter.convertToDatabaseColumn("만성콩팥병");

    assertThat(encrypted).isNotEqualTo("만성콩팥병");
    assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo("만성콩팥병");
    System.out.println("  [실측] 건강정보 암호화 정상 (평문 5자 -> 암호문 "
        + encrypted.length() + "자)");
  }

  @Test
  @DisplayName("같은 값도 매번 다른 암호문이 된다 — 그래서 검색·조인이 불가능하다")
  void nonDeterministic() {
    SensitiveDataConverter converter = new SensitiveDataConverter();
    assertThat(converter.convertToDatabaseColumn("당뇨"))
        .isNotEqualTo(converter.convertToDatabaseColumn("당뇨"));
  }
}
