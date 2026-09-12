package com.meogeodo.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

  Optional<Restaurant> findByContentId(String contentId);

  /**
   * 좌표 사각 범위 안의 식당.
   *
   * <p>정확한 반경 판정과 정렬은 자바에서 한다({@code GeoBox}). 여기서는
   * {@code idx_restaurant_location} 인덱스를 타도록 범위로만 좁힌다.
   *
   * <p>좌표가 없는 식당은 제외한다 — 지도에 찍을 수도, 거리를 잴 수도 없다.
   */
  @Query("""
      SELECT r FROM Restaurant r
      WHERE r.lat BETWEEN :minLat AND :maxLat
        AND r.lng BETWEEN :minLng AND :maxLng
      """)
  List<Restaurant> findInBoundingBox(
      @Param("minLat") BigDecimal minLat,
      @Param("maxLat") BigDecimal maxLat,
      @Param("minLng") BigDecimal minLng,
      @Param("maxLng") BigDecimal maxLng);
}
