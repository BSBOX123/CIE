package com.meogeodo.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 MySQL 에 강릉 데이터를 적재한다. 개발용 시드 도구다.
 *
 * <pre>
 *   SEED_GANGNEUNG=1 ./gradlew test --tests '*SeedGangneungTest'
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SEED_GANGNEUNG", matches = "1")
class SeedGangneungTest {

  @DynamicPropertySource
  static void realDb(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
    registry.add("spring.datasource.username", () -> "meogeodo");
    registry.add("spring.datasource.password", () -> "meogeodo");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("meogeodo.public-data.api-key",
        () -> System.getenv("PUBLIC_DATA_API_KEY"));
    registry.add("meogeodo.security.encryption-key",
        () -> System.getenv("MEOGEODO_ENCRYPTION_KEY"));
    registry.add("meogeodo.security.jwt-secret", () -> System.getenv("JWT_SECRET"));
  }

  @Autowired private IngestService ingest;

  @Test
  void seed() {
    System.out.println("  [시드] " + ingest.ingestArea(32, 1));
  }
}
