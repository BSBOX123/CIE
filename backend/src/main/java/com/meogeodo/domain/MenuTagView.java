package com.meogeodo.domain;

/**
 * 메뉴 1건과 거기 붙은 태그 한 줄 (조회 전용).
 *
 * <p>메뉴 -> dish -> dish_tag 를 한 번에 읽기 위한 평면 구조다. 메뉴마다
 * 쿼리를 날리면 식당 하나에 수십 번 나가게 된다.
 */
public interface MenuTagView {
  Long getRestaurantId();

  Long getMenuId();

  String getMenuName();

  Integer getPrice();

  Boolean getRepresentative();

  Long getDishId();

  /**
   * 태깅됐으면 1, 아니면 0. 0이면 판정을 낼 수 없다는 뜻이다 (SPEC 9.1).
   *
   * <p>시각이 아니라 플래그로 받는다. 네이티브 쿼리 프로젝션에서 MySQL 드라이버가
   * 돌려주는 {@code java.sql.Timestamp} 를 {@code OffsetDateTime} 으로 바꾸지 못해
   * 500 이 났다. 태그가 하나도 없을 때는 값이 전부 null 이라 변환이 일어나지 않아
   * 드러나지 않는다 — 첫 태깅이 들어온 순간 검색과 상세가 동시에 죽는다.
   */
  Integer getTagged();

  /** CARE | ALLERGEN. 태그가 없으면 null. */
  String getTagType();

  String getTagValue();

  /** 알레르겐만 채워진다. MAIN | TRACE. */
  String getAmount();

  String getSource();
}
