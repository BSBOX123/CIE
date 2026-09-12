package com.meogeodo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 식당별 메뉴 인스턴스 (SPEC 4.2). */
@Entity
@Table(name = "menu")
public class Menu {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "restaurant_id", nullable = false)
  private Restaurant restaurant;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "dish_id")
  private Dish dish;

  /** 식당이 적은 원문 그대로. 화면에 이 이름을 보여준다. */
  @Column(name = "raw_name", nullable = false, length = 200)
  private String rawName;

  /**
   * 어떤 공공 API에도 메뉴 가격이 없다. 항상 {@code null} 로 시작하며, 값이
   * 없으면 화면에서 가격을 숨긴다 (SPEC 12.1).
   */
  @Column private Integer price;

  @Column(name = "is_representative", nullable = false)
  private boolean representative = false;

  protected Menu() {}

  public Menu(Restaurant restaurant, String rawName, boolean representative) {
    this.restaurant = restaurant;
    this.rawName = rawName;
    this.representative = representative;
  }

  public Long getId() {
    return id;
  }

  public Restaurant getRestaurant() {
    return restaurant;
  }

  public String getRawName() {
    return rawName;
  }

  public Dish getDish() {
    return dish;
  }

  public void setDish(Dish dish) {
    this.dish = dish;
  }

  public boolean isRepresentative() {
    return representative;
  }

  public Integer getPrice() {
    return price;
  }
}
