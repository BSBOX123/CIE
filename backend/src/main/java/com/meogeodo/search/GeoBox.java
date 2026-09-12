package com.meogeodo.search;

/**
 * 좌표 반경 검색용 사각 범위와 거리 계산.
 *
 * <p>DB에서는 위경도 범위로 <b>대략</b> 걸러 인덱스를 태우고, 정확한 거리는
 * 자바에서 구한다. MySQL의 {@code ST_Distance_Sphere} 를 쓰면 더 간단하지만
 * H2에는 없어서 테스트를 돌릴 수 없다. 반경 안 후보는 많아야 수십 건이라
 * 자바 계산으로 충분하다.
 */
public record GeoBox(double minLat, double maxLat, double minLng, double maxLng) {

  /** 지구 반지름(m). */
  private static final double EARTH_RADIUS_M = 6_371_000;

  /** 위도 1도당 거리(m). 경도는 위도에 따라 줄어들므로 따로 계산한다. */
  private static final double METERS_PER_LAT_DEGREE = 111_320;

  /** 도보 속도(m/분). 고령 사용자를 감안해 보수적으로 잡는다. */
  private static final double WALK_METERS_PER_MINUTE = 67;

  public static GeoBox around(double lat, double lng, int radiusMeters) {
    double latDelta = radiusMeters / METERS_PER_LAT_DEGREE;
    // 고위도로 갈수록 경도 1도의 실제 거리가 짧아진다. cos 보정을 빼면
    // 위도가 높은 지역에서 반경이 과하게 좁아진다.
    double cos = Math.max(Math.cos(Math.toRadians(lat)), 0.01);
    double lngDelta = radiusMeters / (METERS_PER_LAT_DEGREE * cos);
    return new GeoBox(lat - latDelta, lat + latDelta, lng - lngDelta, lng + lngDelta);
  }

  /** 두 좌표 사이 거리(m). Haversine. */
  public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
    double dLat = Math.toRadians(lat2 - lat1);
    double dLng = Math.toRadians(lng2 - lng1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2)
                * Math.sin(dLng / 2);
    return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  /** 도보 예상 시간(분). 최소 1분. */
  public static int walkMinutes(double meters) {
    return Math.max(1, (int) Math.round(meters / WALK_METERS_PER_MINUTE));
  }
}
