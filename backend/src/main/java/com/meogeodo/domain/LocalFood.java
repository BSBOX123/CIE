package com.meogeodo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;

/**
 * 지역 음식 (SPEC 4.2, 7.5).
 *
 * <p>우리가 고르고 쓴 큐레이션이라 DB 에 둔다. 관광공사 데이터가 아니다. 이 음식을
 * 파는 식당은 저장하지 않고 요청 때마다 관광공사에서 {@link #keywordList()} 로 찾는다.
 */
@Entity
@Table(name = "local_food")
public class LocalFood {

  @Id
  @Column(length = 50)
  private String id;

  @Column(nullable = false, length = 100)
  private String name;

  /** 판정에 쓰는 음식 사전 항목. 태깅이 되어 있어야 판정이 나온다. */
  @Column(name = "dish_id")
  private Long dishId;

  /** 법정동 시도 코드 (관광공사 {@code lDongRegnCd}). 26 부산, 27 대구, 31 울산, 47 경북, 48 경남. */
  @Column(name = "ldong_regn_cd", length = 5)
  private String regionCode;

  /** 표시용. 예: "부산", "경북 포항". */
  @Column(name = "region_label", length = 50)
  private String regionLabel;

  @Column(columnDefinition = "TEXT")
  private String description;

  /** 이 음식을 파는 식당을 찾을 검색어. 쉼표로 구분. 예: "아구찜,아귀찜". */
  @Column(length = 200)
  private String keywords;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  /** 설명·팁을 사람이 검수했는지. */
  @Column(nullable = false)
  private boolean verified;

  protected LocalFood() {}

  public LocalFood(String id, String name, Long dishId, String regionCode, String regionLabel,
      String description, String keywords, int sortOrder) {
    this.id = id;
    this.name = name;
    this.dishId = dishId;
    this.regionCode = regionCode;
    this.regionLabel = regionLabel;
    this.description = description;
    this.keywords = keywords;
    this.sortOrder = sortOrder;
  }

  public List<String> keywordList() {
    if (keywords == null || keywords.isBlank()) {
      return List.of(name);
    }
    return Arrays.stream(keywords.split(",")).map(String::trim).filter(k -> !k.isEmpty()).toList();
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public Long getDishId() {
    return dishId;
  }

  public String getRegionCode() {
    return regionCode;
  }

  public String getRegionLabel() {
    return regionLabel;
  }

  public String getDescription() {
    return description;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public boolean isVerified() {
    return verified;
  }
}
