package com.meogeodo.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 거리·범위 계산 테스트. 실제 좌표로 검증한다. */
class GeoBoxTest {

  // 강릉 경포대 / 초당동 (실제 수집 데이터의 좌표)
  private static final double GYEONGPO_LAT = 37.7952, GYEONGPO_LNG = 128.8964;
  private static final double CHODANG_LAT = 37.7611934, CHODANG_LNG = 128.9393320;

  @Test
  @DisplayName("실제 좌표 사이 거리가 상식적인 범위에 든다")
  void realDistance() {
    double m = GeoBox.distanceMeters(GYEONGPO_LAT, GYEONGPO_LNG, CHODANG_LAT, CHODANG_LNG);
    assertThat(m).isBetween(4_000.0, 6_000.0);
  }

  @Test
  @DisplayName("같은 지점은 0m")
  void samePoint() {
    assertThat(GeoBox.distanceMeters(GYEONGPO_LAT, GYEONGPO_LNG, GYEONGPO_LAT, GYEONGPO_LNG))
        .isCloseTo(0.0, within(0.001));
  }

  @Test
  @DisplayName("위도 1도는 약 111km")
  void oneLatitudeDegree() {
    assertThat(GeoBox.distanceMeters(37.0, 128.0, 38.0, 128.0))
        .isCloseTo(111_000, within(1_000.0));
  }

  @Test
  @DisplayName("반경 안의 점은 사각 범위에도 들어간다")
  void boxContainsRadius() {
    GeoBox box = GeoBox.around(GYEONGPO_LAT, GYEONGPO_LNG, 2_000);
    double lat = GYEONGPO_LAT + 1_500 / 111_320.0;
    assertThat(lat).isBetween(box.minLat(), box.maxLat());
  }

  @Test
  @DisplayName("경도 범위는 위도가 높을수록 넓어진다 — cos 보정")
  void longitudeWidensWithLatitude() {
    GeoBox seoul = GeoBox.around(37.5, 127.0, 2_000);
    GeoBox equator = GeoBox.around(0.0, 127.0, 2_000);
    assertThat(seoul.maxLng() - seoul.minLng())
        .isGreaterThan(equator.maxLng() - equator.minLng());
  }

  @Test
  @DisplayName("도보 시간은 거리에 비례하고 최소 1분")
  void walking() {
    assertThat(GeoBox.walkMinutes(402)).isEqualTo(6);
    assertThat(GeoBox.walkMinutes(1_200)).isEqualTo(18);
    assertThat(GeoBox.walkMinutes(5)).isEqualTo(1);
  }
}
