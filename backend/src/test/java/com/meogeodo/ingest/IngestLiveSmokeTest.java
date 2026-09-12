package com.meogeodo.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.meogeodo.domain.DishRepository;
import com.meogeodo.domain.MenuRepository;
import com.meogeodo.domain.RestaurantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 KorService2를 호출하는 스모크 테스트.
 *
 * <p>공공 API를 진짜로 부르므로 {@code PUBLIC_DATA_API_KEY} 가 설정된 경우에만
 * 돈다. 목으로 대체한 테스트가 잡지 못하는 것 — 응답 스키마 변경, 인코딩된
 * 인증키 처리, 실제 메뉴 텍스트의 다양성 — 을 확인한다.
 *
 * <pre>
 *   PUBLIC_DATA_API_KEY='...' ./gradlew test --tests '*IngestLiveSmokeTest'
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PUBLIC_DATA_API_KEY", matches = ".+")
class IngestLiveSmokeTest {

  @DynamicPropertySource
  static void realApiKey(DynamicPropertyRegistry registry) {
    registry.add("meogeodo.public-data.api-key", () -> System.getenv("PUBLIC_DATA_API_KEY"));
    registry.add(
        "meogeodo.public-data.kor-service-base-url",
        () -> "https://apis.data.go.kr/B551011/KorService2");
  }

  @Autowired private IngestService ingestService;
  @Autowired private KorServiceClient client;
  @Autowired private RestaurantRepository restaurants;
  @Autowired private MenuRepository menus;
  @Autowired private DishRepository dishes;

  @Test
  @DisplayName("인코딩된 인증키로 전국 건수를 조회한다")
  void totalCountWorks() {
    // 키가 재인코딩되면 인증이 깨져 0이 돌아온다
    assertThat(client.totalCount(null)).isGreaterThan(1000);
  }

  @Test
  @DisplayName("강릉 한 페이지를 실제로 수집한다")
  void ingestsRealGangneung() {
    var result = ingestService.ingestArea(32, 1);

    assertThat(result.fetched()).isPositive();
    assertThat(result.created()).isPositive();
    assertThat(restaurants.count()).isPositive();

    System.out.println("  [실측] " + result);
    System.out.println("  [실측] 메뉴 " + menus.count() + "건 -> 고유 음식 " + dishes.count() + "건");

    // dish 정규화가 실제로 중복을 걷어내는지 — 태깅 비용의 근거
    assertThat(dishes.count()).isLessThanOrEqualTo(menus.count());
  }

  @Test
  @DisplayName("좌표 반경 조회가 거리(dist)를 함께 준다")
  void locationSearchReturnsDistance() {
    var items = client.locationBasedList(37.7952, 128.8964, 2000, 5);

    assertThat(items).isNotEmpty();
    assertThat(items.get(0).dist()).isNotBlank();
    System.out.println(
        "  [실측] 가장 가까운 곳: " + items.get(0).title() + " " + items.get(0).dist() + "m");
  }
}
