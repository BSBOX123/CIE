package com.meogeodo.tour;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 한국관광공사 국문관광정보(KorService2) — 요청 시점에 실시간으로 부른다.
 *
 * <p>공모전 요구사항: 공사 OpenAPI 데이터를 로컬 DB 에 저장하거나 캐싱해 서빙하지
 * 않는다. 심사에서 실제 호출 내역으로 활용 여부를 검증한다. 예전에는 전국 식당을
 * DB 에 적재해 조회했는데, 적재 중 초당 한도에 걸린 응답을 "메뉴 없음"으로 저장해
 * 전체 식당의 86% 가 메뉴 없는 것으로 보이는 사고가 있었다. 실시간 조회는 이런
 * 동기화 오류가 생길 자리가 없다.
 *
 * <p><b>실패를 빈 결과로 바꾸지 않는다.</b> 한도 초과·장애는 {@link TourApiException}
 * 으로 알린다. "결과 없음"과 "불러오지 못함"을 섞으면 같은 사고가 난다.
 */
public interface TourApi {

  /**
   * 좌표 기준 반경 안의 음식점. 가까운 순.
   *
   * @param pageNo 1부터 시작
   */
  NearbyPage nearby(double lat, double lng, int radiusMeters, int pageNo, int rows);

  /**
   * 이름에 검색어가 들어간 음식점 (전국). 거리는 채워지지 않는다.
   *
   * <p>지역 음식을 파는 곳을 찾는 데 쓴다. 한국 식당은 상호에 파는 음식이 들어가는
   * 경우가 많다 (예: 청도돼지국밥, 초량밀면).
   */
  List<Place> keyword(String keyword, int rows);

  /** 식당 기본 정보 (이름·주소·좌표·이미지). 없는 식당이면 비어 있다. */
  Optional<Place> place(String contentId);

  /** 식당 소개 정보 (메뉴·전화·영업시간). 없는 식당이면 비어 있다. */
  Optional<Intro> intro(String contentId);

  /**
   * 여러 식당의 소개 정보를 한꺼번에. 불러오지 못한 식당은 결과에서 빠진다 —
   * 호출하는 쪽이 "없음"과 "실패"를 구분할 수 있도록 {@code Optional.empty()} 로
   * 채우지 않는다.
   */
  Map<String, Optional<Intro>> intros(Collection<String> contentIds);

  /** 여러 식당의 기본 정보를 한꺼번에. 불러오지 못한 식당은 결과에서 빠진다. */
  Map<String, Optional<Place>> places(Collection<String> contentIds);

  /** 반경 조회 결과. {@code totalCount} 는 반경 안 전체 건수. */
  record NearbyPage(List<Place> places, int totalCount) {}

  /**
   * 식당 기본 정보.
   *
   * @param distanceMeters 반경 조회에서만 채워진다 (API 가 계산해 준다)
   * @param regionCode 법정동 시도 코드 {@code lDongRegnCd} (예: 26 부산, 48 경남)
   * @param districtCode 법정동 시군구 코드 {@code lDongSignguCd}
   */
  record Place(
      String contentId,
      String title,
      String addr1,
      Double lat,
      Double lng,
      Double distanceMeters,
      String firstImage,
      String regionCode,
      String districtCode) {

    /** 다른 기준점에서 잰 거리로 바꾼 사본. */
    public Place withDistance(Double meters) {
      return new Place(contentId, title, addr1, lat, lng, meters, firstImage,
          regionCode, districtCode);
    }
  }

  /** 식당 소개 정보. 메뉴 원문({@code firstmenu}·{@code treatmenu})은 여기에만 있다. */
  record Intro(
      String firstMenu,
      String treatMenu,
      String tel,
      String openTime,
      String restDate,
      String parking) {

    public boolean hasMenu() {
      return (firstMenu != null && !firstMenu.isBlank())
          || (treatMenu != null && !treatMenu.isBlank());
    }
  }
}
