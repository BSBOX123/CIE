package com.meogeodo.tour;

/**
 * 관광공사 API 를 불러오지 못했다. 한도 초과·장애·인증 오류.
 *
 * <p>"결과 없음"과 구분하기 위한 예외다. 이걸 빈 목록으로 바꾸면 적재 시절의
 * 사고(한도 초과가 "메뉴 없음"으로 저장됨)가 그대로 되풀이된다.
 */
public class TourApiException extends RuntimeException {

  public TourApiException(String message) {
    super(message);
  }

  public TourApiException(String message, Throwable cause) {
    super(message, cause);
  }
}
