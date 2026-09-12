package com.meogeodo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * 식당 속성 (SPEC 2.7, 6.5).
 *
 * <p>어떤 공공 API에도 없는 값이라 후기에서 도출하거나 관리자가 넣는다.
 * 무장애 API는 음식점에 대해 전 필드가 공란이었다.
 */
@Entity
@Table(name = "restaurant_flag")
@IdClass(RestaurantFlag.Key.class)
public class RestaurantFlag {

  /** 값의 출처. 우선순위 판단과 신뢰도 표시에 쓴다. */
  public enum Source {
    PUBLIC_API,
    COMMUNITY,
    ADMIN
  }

  @Id
  @Column(name = "restaurant_id")
  private Long restaurantId;

  @Id
  @Column(name = "flag", length = 30)
  private String flag;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Source source;

  protected RestaurantFlag() {}

  public RestaurantFlag(Long restaurantId, String flag, Source source) {
    this.restaurantId = restaurantId;
    this.flag = flag;
    this.source = source;
  }

  public Long getRestaurantId() {
    return restaurantId;
  }

  public String getFlag() {
    return flag;
  }

  public Source getSource() {
    return source;
  }

  public void setSource(Source source) {
    this.source = source;
  }

  /** 복합 키. */
  public static class Key implements Serializable {
    private Long restaurantId;
    private String flag;

    public Key() {}

    public Key(Long restaurantId, String flag) {
      this.restaurantId = restaurantId;
      this.flag = flag;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      return other instanceof Key k
          && Objects.equals(restaurantId, k.restaurantId)
          && Objects.equals(flag, k.flag);
    }

    @Override
    public int hashCode() {
      return Objects.hash(restaurantId, flag);
    }
  }
}
