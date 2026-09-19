package com.meogeodo.tour;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 실제 관광공사 API 로 확인한다. 키가 있을 때만 돈다 — CI·평소 테스트에서는 건너뛴다.
 *
 * <pre>PUBLIC_DATA_API_KEY=... ./gradlew test --tests '*TourApiClientLiveTest'</pre>
 */
@EnabledIfEnvironmentVariable(named = "PUBLIC_DATA_API_KEY", matches = ".+")
class TourApiClientLiveTest {

  // 강릉 초당동
  private static final double LAT = 37.7911, LNG = 128.9150;

  private final TourApiClient client = new TourApiClient(
      "https://apis.data.go.kr/B551011/KorService2",
      System.getenv("PUBLIC_DATA_API_KEY"), 5, new ObjectMapper());

  @Test
  @DisplayName("반경 조회 → 소개 정보까지 실제로 받아 온다")
  void nearbyThenIntros() {
    long started = System.nanoTime();
    var page = client.nearby(LAT, LNG, 2_000, 1, 20);
    var intros = client.intros(page.places().stream().map(TourApi.Place::contentId).toList());
    long millis = (System.nanoTime() - started) / 1_000_000;

    System.out.printf("반경 %d곳 중 %d곳, 소개 %d건, 메뉴 있음 %d건, %dms%n",
        page.totalCount(), page.places().size(), intros.size(),
        intros.values().stream().filter(i -> i.map(TourApi.Intro::hasMenu).orElse(false)).count(),
        millis);

    assertThat(page.places()).isNotEmpty();
    assertThat(page.places()).allSatisfy(p -> {
      assertThat(p.contentId()).isNotBlank();
      assertThat(p.distanceMeters()).isNotNull().isLessThanOrEqualTo(2_000.0);
    });
    assertThat(page.places()).extracting(TourApi.Place::distanceMeters).isSorted();
    // 일부가 한도에 걸려 빠질 수는 있어도, 전부 빠지면 클라이언트가 잘못된 것이다.
    assertThat(intros).isNotEmpty();
  }

  @Test
  @DisplayName("식당 기본 정보와 없는 식당")
  void placeAndMissing() {
    String id = client.nearby(LAT, LNG, 2_000, 1, 1).places().get(0).contentId();
    var place = client.place(id);
    assertThat(place).isPresent();
    assertThat(place.get().title()).isNotBlank();
    assertThat(place.get().lat()).isBetween(37.0, 38.5);

    assertThat(client.place("1")).isEmpty();
  }

  @Test
  @DisplayName("반경 안에 아무것도 없으면 빈 목록 (items 가 빈 문자열로 온다)")
  void emptyArea() {
    // 동해 한가운데
    var page = client.nearby(37.5, 131.5, 1_000, 1, 10);
    assertThat(page.places()).isEmpty();
    assertThat(page.totalCount()).isZero();
  }

  @Test
  @DisplayName("동시에 많이 불러도 초당 한도에 무너지지 않는다")
  void burst() {
    List<String> ids = client.nearby(LAT, LNG, 5_000, 1, 40).places().stream()
        .map(TourApi.Place::contentId).toList();
    var intros = client.intros(ids);
    System.out.printf("동시 %d건 요청 → %d건 성공%n", ids.size(), intros.size());
    assertThat(intros.size()).isGreaterThanOrEqualTo(ids.size() * 9 / 10);
  }
}
