package com.meogeodo.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 MySQL 에 공공데이터 음식점을 적재한다. 개발·배포용 시드 도구다.
 *
 * <p>GPS 반경 검색 서비스라 사용자가 어디에 있든 결과가 나와야 한다. 기본값이
 * 전국인 이유다.
 *
 * <pre>
 *   # 전국 (기본값) — 13,000여 건, 1시간 이상 걸린다
 *   SEED_INGEST=1 ./gradlew test --tests '*SeedIngestTest'
 *
 *   # 특정 지역만 (강원 32, 서울 1, 부산 6 ...)
 *   SEED_INGEST=1 SEED_AREA_CODE=32 SEED_MAX_PAGES=1 ./gradlew test --tests '*SeedIngestTest'
 * </pre>
 *
 * <p>중간에 끊겨도 그대로 다시 돌리면 된다. 이미 받은 식당은 {@code modifiedtime}
 * 이 같으면 상세 조회를 건너뛰므로 빠르게 지나간다.
 *
 * <p>DB 접속 정보는 환경변수에서 읽는다. 운영 DB 비밀번호를 코드에 박아 둘 수 없다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SEED_INGEST", matches = "1")
class SeedIngestTest {

  @DynamicPropertySource
  static void realDb(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
    registry.add("spring.datasource.username", () -> envOr("DB_USERNAME", "meogeodo"));
    registry.add("spring.datasource.password", () -> envOr("DB_PASSWORD", "meogeodo"));
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("meogeodo.public-data.api-key",
        () -> System.getenv("PUBLIC_DATA_API_KEY"));
    registry.add("meogeodo.security.encryption-key",
        () -> System.getenv("MEOGEODO_ENCRYPTION_KEY"));
    registry.add("meogeodo.security.jwt-secret", () -> System.getenv("JWT_SECRET"));
  }

  private static String envOr(String name, String fallback) {
    String v = System.getenv(name);
    return (v == null || v.isBlank()) ? fallback : v;
  }

  @Autowired private IngestService ingest;

  @Test
  void seed() {
    // 비어 있으면 전국. 지역 코드를 주면 그 지역만.
    Integer areaCode = null;
    String raw = System.getenv("SEED_AREA_CODE");
    if (raw != null && !raw.isBlank() && !"all".equalsIgnoreCase(raw)) {
      areaCode = Integer.parseInt(raw.trim());
    }
    // 0 이면 전체 페이지.
    int maxPages = Integer.parseInt(envOr("SEED_MAX_PAGES", "0"));

    System.out.println(
        "  [시드] 시작 — areaCode=" + (areaCode == null ? "전국" : areaCode)
            + ", maxPages=" + (maxPages <= 0 ? "전체" : maxPages));
    System.out.println("  [시드] " + ingest.ingestArea(areaCode, maxPages));
  }
}
