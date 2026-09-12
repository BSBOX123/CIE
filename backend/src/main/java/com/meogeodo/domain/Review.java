package com.meogeodo.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 제보 (MVP 4번, SPEC 4.2).
 *
 * <p>방문한 사람이 남기는 한 건에는 두 가지가 들어간다.
 *
 * <ul>
 *   <li>가게에 대한 평가 — {@code ok} 와 자유 서술
 *   <li>자신의 주문 방법 — {@code requests}, 그때 실제로 사용한 요청 문구
 * </ul>
 *
 * <p>{@code feedback} 은 정해진 문구 중에서 고른 것이며, 쌓이면
 * {@code restaurant_flag} 를 채운다 (SPEC 6.5).
 */
@Entity
@Table(name = "review")
public class Review {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "restaurant_id", nullable = false)
  private Long restaurantId;

  @Column(name = "user_id")
  private Long userId;

  /** 요청이 받아들여졌는지. 화면의 ○/✕ 낙관으로 표시된다. */
  @Column(name = "is_ok", nullable = false)
  private boolean ok;

  @Column(columnDefinition = "text")
  private String note;

  /** 그때 실제로 사용한 요청 문구. 다음 사람에게 가장 쓸모 있는 정보다. */
  @ElementCollection
  @CollectionTable(name = "review_request", joinColumns = @JoinColumn(name = "review_id"))
  @Column(name = "phrase", nullable = false, length = 120)
  @OrderColumn(name = "sort_order")
  private List<String> requests = new ArrayList<>();

  /** 정해진 피드백 문구. flag 도출의 근거가 된다. */
  @ElementCollection
  @CollectionTable(name = "review_feedback", joinColumns = @JoinColumn(name = "review_id"))
  @Column(name = "phrase", nullable = false, length = 120)
  private Set<String> feedback = new LinkedHashSet<>();

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected Review() {}

  public Review(Long restaurantId, Long userId, boolean ok, String note) {
    this.restaurantId = restaurantId;
    this.userId = userId;
    this.ok = ok;
    this.note = note;
  }

  public void setRequests(List<String> requests) {
    this.requests = new ArrayList<>(requests);
  }

  public void setFeedback(Set<String> feedback) {
    this.feedback = new LinkedHashSet<>(feedback);
  }

  public Long getId() {
    return id;
  }

  public Long getRestaurantId() {
    return restaurantId;
  }

  public Long getUserId() {
    return userId;
  }

  public boolean isOk() {
    return ok;
  }

  public String getNote() {
    return note;
  }

  public List<String> getRequests() {
    return requests;
  }

  public Set<String> getFeedback() {
    return feedback;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
