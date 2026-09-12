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

  /** 태깅 전이면 null. 판정을 낼 수 없다는 뜻이다 (SPEC 9.1). */
  java.time.OffsetDateTime getTaggedAt();

  /** CARE | ALLERGEN. 태그가 없으면 null. */
  String getTagType();

  String getTagValue();

  /** 알레르겐만 채워진다. MAIN | TRACE. */
  String getAmount();

  String getSource();
}
