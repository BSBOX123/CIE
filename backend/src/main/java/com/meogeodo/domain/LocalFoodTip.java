package com.meogeodo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 지역 음식을 주문할 때 쓸 만한 요청 문구. */
@Entity
@Table(name = "local_food_tip")
public class LocalFoodTip {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "food_id", nullable = false, length = 50)
  private String foodId;

  @Column(nullable = false, length = 120)
  private String phrase;

  /** V1 에서 SMALLINT 로 만들었다. int 로 두면 운영의 스키마 검증(validate)에서 기동이 실패한다. */
  @Column(name = "sort_order", nullable = false)
  private short sortOrder;

  protected LocalFoodTip() {}

  public LocalFoodTip(String foodId, String phrase, int sortOrder) {
    this.foodId = foodId;
    this.phrase = phrase;
    this.sortOrder = (short) sortOrder;
  }

  public String getFoodId() {
    return foodId;
  }

  public String getPhrase() {
    return phrase;
  }
}
