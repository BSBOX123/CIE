package com.meogeodo.card;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 주문요청카드 (SPEC 9.5). */
public final class CardDtos {

  private CardDtos() {}

  /**
   * 카드 생성 요청.
   *
   * <p>{@code requests} 를 보내지 않으면 서버가 메뉴 판정과 사용자 상용 문구로
   * 채운다. 보내면 그것을 그대로 쓴다 — 사용자가 화면에서 고른 결과다.
   */
  @Schema(description = "카드 생성 요청")
  public record CreateRequest(
      @Schema(description = "어느 식당에서 쓸 카드인지", example = "1") Long restaurantId,
      @Schema(description = "어떤 메뉴를 주문할지", example = "10") Long menuId,
      @Schema(
              description =
                  """
                  카드에 넣을 요청 문구. **보내지 않으면** 서버가 메뉴 판정과 사용자 상용 문구로
                  채운다. 보내면 그것을 그대로 쓴다 — 사용자가 화면에서 고른 결과이기 때문이다.""",
              example = "[\"국물은 따로 주세요\", \"고춧가루 빼주세요\"]")
          @Size(max = 20)
          List<String> requests) {}

  /** 카드에 인쇄될 문구 1건. */
  @Schema(description = "카드에 인쇄될 문구 1건")
  public record CardRequest(
      @Schema(description = "카드에 실제로 찍히는 문구", example = "국물은 따로 주시면 감사하겠습니다")
          String phrase,
      @Schema(description = "다듬기 전 원문", example = "국물은 따로 주세요") String original,
      @Schema(
              description = "false 면 다듬기에 실패해 원문을 그대로 쓴 것이다. 카드 생성은 실패하지 않는다",
              example = "true")
          boolean polished) {}

  /**
   * 카드 내용. 실제 렌더링(canvas)은 클라이언트가 한다 (D13).
   *
   * <p>필드 순서가 카드의 위에서 아래 순서와 같다.
   */
  @Schema(description = "카드 내용. 필드 순서가 카드의 위에서 아래 순서와 같다")
  public record CardResponse(
      Long id,
      @Schema(description = "맨 위 알레르기 경고 띠. 없으면 null", example = "새우 알레르기가 있습니다")
          String allergyBanner,
      @Schema(description = "지병 줄", example = "당뇨·고혈압이 있습니다") String diseaseLine,
      @Schema(description = "주문할 메뉴 줄", example = "초당순두부 1인분") String menuLine,
      @Schema(description = "요청 문구들") List<CardRequest> requests,
      @Schema(
              description = "복용약 줄. 프로필의 showMedsOnCard 가 false 면 null 이다",
              example = "혈압약을 복용 중입니다")
          String medsLine,
      @Schema(description = "카드 아래쪽 안내 문구들") List<String> footer,
      @Schema(description = "화면에 그대로 노출해야 하는 고지 문구") String disclaimer) {}

  // ── ai-service 연동 (내부용. 프론트가 직접 호출하지 않는다) ──
  @Schema(hidden = true)
  public record PolishRequest(List<String> phrases, List<String> mustKeep, String menuLabel) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PolishedPhrase(String original, String polished, boolean fellBack) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PolishResponse(List<PolishedPhrase> phrases, String modelId) {}
}
