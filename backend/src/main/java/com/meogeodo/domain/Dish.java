package com.meogeodo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 정규화된 음식 (SPEC 4.2).
 *
 * <p>전국 식당의 메뉴 인스턴스는 약 5만 건이지만 고유 음식명은 훨씬 적다.
 * {@code dish} 단위로 한 번만 태깅하면 LLM 비용이 크게 줄고, 무엇보다 같은
 * 음식이 어느 식당에서나 같은 판정을 받는다.
 */
@Entity
@Table(name = "dish")
public class Dish {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "normalized_name", nullable = false, unique = true, length = 100)
  private String normalizedName;

  /** 매칭된 식약처 {@code FOOD_CD}. */
  @Column(name = "nutrition_food_cd", length = 30)
  private String nutritionFoodCd;

  /** 식약처 {@code FOOD_CAT1_NM}. 1회 섭취량 결정에 쓴다 (SPEC 7.3). */
  @Column(name = "food_category", length = 50)
  private String foodCategory;

  /**
   * 조회한 영양성분(100g 기준). 키는 SPEC 6.4의 영양소 이름.
   *
   * <p>ai-service를 무상태로 두기 위해 여기 보관했다가, 배치 결과를 수집할 때
   * 그대로 돌려보낸다. 값이 {@code null} 인 항목은 <b>미측정</b>이며 0이 아니다
   * — 0으로 읽으면 '주의 성분 없음'으로 잘못 판정된다.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "json")
  private Map<String, Double> nutrition;

  /** 식품군 기본 섭취량을 덮어쓸 때 (예: 물회는 주식으로 먹는다). */
  @Column(name = "portion_g_override", precision = 6, scale = 1)
  private BigDecimal portionGramsOverride;

  @Column(name = "tagged_at")
  private OffsetDateTime taggedAt;

  @Column(name = "model_id", length = 50)
  private String modelId;

  @Column(name = "needs_review", nullable = false)
  private boolean needsReview = false;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected Dish() {}

  public Dish(String normalizedName) {
    this.normalizedName = normalizedName;
  }

  /** 아직 태깅되지 않았으면 판정을 낼 수 없다 (SPEC 9.1 tagStatus=PENDING). */
  public boolean isTagged() {
    return taggedAt != null;
  }

  public Long getId() {
    return id;
  }

  public String getNormalizedName() {
    return normalizedName;
  }

  public Map<String, Double> getNutrition() {
    return nutrition;
  }

  public void setNutrition(Map<String, Double> nutrition) {
    this.nutrition = nutrition;
  }

  public String getFoodCategory() {
    return foodCategory;
  }

  public void setFoodCategory(String foodCategory) {
    this.foodCategory = foodCategory;
  }

  public void setNutritionFoodCd(String nutritionFoodCd) {
    this.nutritionFoodCd = nutritionFoodCd;
  }

  public BigDecimal getPortionGramsOverride() {
    return portionGramsOverride;
  }

  public void setTaggedAt(OffsetDateTime taggedAt) {
    this.taggedAt = taggedAt;
  }

  public void setModelId(String modelId) {
    this.modelId = modelId;
  }

  public boolean isNeedsReview() {
    return needsReview;
  }

  public void setNeedsReview(boolean needsReview) {
    this.needsReview = needsReview;
  }
}
