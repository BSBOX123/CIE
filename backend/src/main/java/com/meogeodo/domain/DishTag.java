package com.meogeodo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 음식별 주의성분·알레르기 태그 (SPEC 4.2).
 *
 * <p>{@code source} 와 {@code confidence} 를 반드시 남긴다. 수치 근거가 있는
 * 태그와 LLM 추정을 구분해야 화면에 "추정" 표시를 할 수 있고(SPEC 8.3),
 * 낮은 신뢰도를 검수 큐로 보낼 수 있다.
 */
@Entity
@Table(name = "dish_tag")
public class DishTag {

  /** SPEC 7.2 우선순위: ADMIN_VERIFIED > NUTRITION_DB > COMMUNITY > LLM */
  public enum Source {
    ADMIN_VERIFIED,
    NUTRITION_DB,
    COMMUNITY,
    LLM;

    /** 값이 클수록 우선한다. */
    public int rank() {
      return values().length - ordinal();
    }
  }

  public enum TagType {
    CARE,
    ALLERGEN
  }

  /**
   * 알레르겐이 음식에 들어가는 정도 (SPEC 3.1).
   *
   * <p>주의성분 태그에서는 쓰지 않는다({@code null}).
   */
  public enum Amount {
    /** 주재료. 빼면 그 음식이 아니게 된다. */
    MAIN,
    /** 양념·부재료에 미량. 빼달라고 요청할 여지가 있다. */
    TRACE
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "dish_id", nullable = false)
  private Dish dish;

  @Enumerated(EnumType.STRING)
  @Column(name = "tag_type", nullable = false, length = 10)
  private TagType tagType;

  @Column(name = "tag_value", nullable = false, length = 30)
  private String tagValue;

  @Enumerated(EnumType.STRING)
  @Column(length = 10)
  private Amount amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Source source;

  @Column(nullable = false, precision = 3, scale = 2)
  private BigDecimal confidence;

  @Column(columnDefinition = "text")
  private String evidence;

  @Column(name = "model_id", length = 50)
  private String modelId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected DishTag() {}

  public DishTag(
      Dish dish, TagType tagType, String tagValue, Source source, BigDecimal confidence) {
    this.dish = dish;
    this.tagType = tagType;
    this.tagValue = tagValue;
    this.source = source;
    this.confidence = confidence;
  }

  public Long getId() {
    return id;
  }

  public Dish getDish() {
    return dish;
  }

  public TagType getTagType() {
    return tagType;
  }

  public String getTagValue() {
    return tagValue;
  }

  public Amount getAmount() {
    return amount;
  }

  public void setAmount(Amount amount) {
    this.amount = amount;
  }

  /** 주재료로 들어간 알레르겐인지. 판정이 갈리는 지점이다. */
  public boolean isMainAllergen() {
    return tagType == TagType.ALLERGEN && amount == Amount.MAIN;
  }

  public Source getSource() {
    return source;
  }

  public void setSource(Source source) {
    this.source = source;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public void setConfidence(BigDecimal confidence) {
    this.confidence = confidence;
  }

  public String getEvidence() {
    return evidence;
  }

  public void setEvidence(String evidence) {
    this.evidence = evidence;
  }

  public void setModelId(String modelId) {
    this.modelId = modelId;
  }

  /** LLM 추정 태그는 화면에 "추정"으로 표시해야 한다 (SPEC 8.3). */
  public boolean isEstimated() {
    return source == Source.LLM;
  }
}
