package com.meogeodo.report;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** 제보 (MVP 4번, SPEC 9.6). */
public final class ReportDtos {

  private ReportDtos() {}

  /**
   * 제보 등록.
   *
   * @param ok 요청이 받아들여졌는지 (가게에 대한 평가)
   * @param requests 그때 실제로 사용한 요청 문구 (자신의 주문 방법)
   * @param feedback 정해진 피드백 문구 중 고른 것 — flag 도출의 근거
   */
  @Schema(description = "제보 등록 요청")
  public record CreateRequest(
      @Schema(description = "어느 식당에 대한 제보인지", example = "1",
              requiredMode = Schema.RequiredMode.REQUIRED)
          @NotNull
          Long restaurantId,
      @Schema(description = "요청이 받아들여졌는지 — 가게에 대한 평가", example = "true",
              requiredMode = Schema.RequiredMode.REQUIRED)
          @NotNull
          Boolean ok,
      @Schema(description = "자유 메모. 자유 문장은 여기에만 넣을 것") @Size(max = 1000) String note,
      @Schema(description = "그때 실제로 사용한 요청 문구 — 다른 사용자에게 주문 방법으로 보인다",
              example = "[\"국물은 따로 주세요\"]")
          @Size(max = 20)
          List<String> requests,
      @Schema(
              description =
                  """
                  GET /api/reports/options 의 문구 중에서 고른 것. flag 도출의 근거라
                  **문구를 그대로** 보내야 한다. 철자가 다르면 집계에서 빠진다.""",
              example = "[\"덜짜게 해줌\"]")
          Set<String> feedback) {}

  /**
   * 제보 1건.
   *
   * <p>{@code author} 는 {@code "60대 · 당뇨"} 처럼 파생한 값이다. 나이와 질환을
   * 그대로 노출하지 않는다 (SPEC 11.4).
   */
  @Schema(description = "제보 1건")
  public record ReviewView(
      Long id,
      @Schema(description = "작성자 표기. 나이·이름 대신 파생한 값이다", example = "60대 · 당뇨")
          String author,
      @Schema(description = "내가 쓴 제보인지. 비로그인이면 항상 false", example = "false")
          boolean mine,
      @Schema(description = "방문한 날", example = "2026-09-11") LocalDate date,
      @Schema(description = "요청이 받아들여졌는지", example = "true") boolean ok,
      @Schema(description = "ok 에 대응하는 기호", example = "○") String symbol,
      @Schema(description = "자유 메모") String note,
      @Schema(description = "그때 사용한 요청 문구") List<String> requests,
      @Schema(description = "고른 피드백 문구") List<String> feedback) {}

  @Schema(description = "식당의 제보 목록")
  public record ReviewListResponse(
      Long restaurantId,
      @Schema(description = "제보 수", example = "3") int count,
      List<ReviewView> reviews,
      @Schema(
              description = "같은 피드백이 2건 이상 모여 이 식당에 실제로 붙은 속성. 검색 filter 에 쓴다",
              example = "[\"덜짜게 해줌\"]")
          List<String> derivedFlags) {}

  /** 제보 화면에서 고를 수 있는 문구들. */
  @Schema(description = "제보 화면에서 고를 수 있는 문구들. 이 문자열을 그대로 보내야 한다")
  public record FeedbackOptionsResponse(
      @Schema(description = "가게에 대한 피드백 문구", example = "[\"덜짜게 해줌\"]")
          List<String> feedbackPhrases,
      @Schema(description = "주문할 때 쓰는 요청 문구", example = "[\"국물은 따로 주세요\"]")
          List<String> requestPhrases) {}
}
