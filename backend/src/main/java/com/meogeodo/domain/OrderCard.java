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
import java.util.List;

/**
 * 주문요청카드 (SPEC 4.2, 9.5).
 *
 * <p>사용자가 매장에 보여준 내용을 남긴다. 프로필이 나중에 바뀌어도 "그때 무엇을
 * 요청했는지" 가 보존되어야 방문 기록과 대조할 수 있다.
 */
@Entity
@Table(name = "order_card")
public class OrderCard {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "restaurant_id")
  private Long restaurantId;

  @Column(name = "menu_id")
  private Long menuId;

  @Column(name = "menu_label", length = 300)
  private String menuLabel;

  /** 카드에 인쇄된 요청 문구. 순서가 곧 카드에 찍히는 순서다. */
  @ElementCollection
  @CollectionTable(name = "order_card_request", joinColumns = @JoinColumn(name = "card_id"))
  @Column(name = "phrase", nullable = false, length = 120)
  @OrderColumn(name = "sort_order")
  private List<String> requests = new ArrayList<>();

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected OrderCard() {}

  public OrderCard(Long userId, Long restaurantId, Long menuId, String menuLabel) {
    this.userId = userId;
    this.restaurantId = restaurantId;
    this.menuId = menuId;
    this.menuLabel = menuLabel;
  }

  public void setRequests(List<String> requests) {
    this.requests = new ArrayList<>(requests);
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public Long getRestaurantId() {
    return restaurantId;
  }

  public Long getMenuId() {
    return menuId;
  }

  public String getMenuLabel() {
    return menuLabel;
  }

  public List<String> getRequests() {
    return requests;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
