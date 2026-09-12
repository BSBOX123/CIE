package com.meogeodo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 태깅 실행 1회 기록 (SPEC 7.1 [5]).
 *
 * <p>무료 티어의 일일 요청 한도 때문에 태깅은 여러 날에 걸쳐 나눠 돌게 된다.
 * 진행 상황과 실패율을 볼 수 있어야 어디까지 됐는지 알 수 있다.
 */
@Entity
@Table(name = "tagging_run")
public class TaggingRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private int requested;

  @Column(nullable = false)
  private int tagged;

  @Column(nullable = false)
  private int failed;

  @Column(name = "model_id", length = 50)
  private String modelId;

  @Column(name = "started_at", nullable = false)
  private OffsetDateTime startedAt = OffsetDateTime.now();

  @Column(name = "finished_at")
  private OffsetDateTime finishedAt;

  @Column(length = 500)
  private String note;

  protected TaggingRun() {}

  public TaggingRun(int requested) {
    this.requested = requested;
  }

  public void finish(int tagged, int failed, String modelId) {
    this.tagged = tagged;
    this.failed = failed;
    this.modelId = modelId;
    this.finishedAt = OffsetDateTime.now();
  }

  public void fail(String reason) {
    this.note = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    this.finishedAt = OffsetDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public int getRequested() {
    return requested;
  }

  public int getTagged() {
    return tagged;
  }

  public int getFailed() {
    return failed;
  }

  public String getNote() {
    return note;
  }
}
