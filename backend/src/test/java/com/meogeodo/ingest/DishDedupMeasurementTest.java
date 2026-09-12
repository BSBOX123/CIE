package com.meogeodo.ingest;

import com.meogeodo.domain.DishRepository;
import com.meogeodo.domain.MenuRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * dish 정규화가 실제로 태깅 대상을 얼마나 줄이는지 측정한다.
 *
 * <p>테스트가 아니라 계측 도구다. 전국 태깅 배치의 LLM 비용이 고유 음식 수에
 * 비례하므로, 그 수를 실측해 계획의 근거로 삼는다. {@code MEASURE_DEDUP=1} 일
 * 때만 돈다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MEASURE_DEDUP", matches = "1")
class DishDedupMeasurementTest {

  @DynamicPropertySource
  static void realApi(DynamicPropertyRegistry registry) {
    registry.add("meogeodo.public-data.api-key", () -> System.getenv("PUBLIC_DATA_API_KEY"));
    registry.add(
        "meogeodo.public-data.kor-service-base-url",
        () -> "https://apis.data.go.kr/B551011/KorService2");
  }

  @Autowired private IngestService ingestService;
  @Autowired private MenuRepository menus;
  @Autowired private DishRepository dishes;

  /** 서울, 강원, 경북, 전남, 제주 — 지역색이 다른 곳을 고른다. */
  private static final int[] AREAS = {1, 32, 35, 38, 39};

  @Test
  @DisplayName("지역을 넓혀갈수록 중복 제거율이 어떻게 변하는지")
  void measure() {
    System.out.println("\n  지역     누적메뉴  누적음식  중복제거율");
    System.out.println("  ------------------------------------------");
    for (int area : AREAS) {
      ingestService.ingestArea(area, 1); // 지역당 100건
      long m = menus.count();
      long d = dishes.count();
      double saved = m == 0 ? 0 : (1 - (double) d / m) * 100;
      System.out.printf("  area=%-3d %8d %9d %9.1f%%%n", area, m, d, saved);
    }
    long m = menus.count();
    long d = dishes.count();
    System.out.printf("%n  표본 %d개 식당 -> 메뉴 %d건, 고유 음식 %d건%n", AREAS.length * 100, m, d);
    System.out.printf("  전국 13,495개 식당 환산: 메뉴 약 %,d건%n", m * 13495 / (AREAS.length * 100));
  }
}
