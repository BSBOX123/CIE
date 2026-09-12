package com.meogeodo.web;

import com.meogeodo.card.CardDtos.CardResponse;
import com.meogeodo.card.CardDtos.CreateRequest;
import com.meogeodo.card.OrderCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 주문요청카드 (SPEC 9.5, MVP 3번). */
@RestController
@RequestMapping("/api/order-cards")
@Tag(
    name = "4. 주문요청카드",
    description =
        """
        가게에 보여 줄 카드. 서버는 **문구만** 만들고, 카드 이미지 렌더링(canvas)은
        클라이언트가 한다. 응답 필드의 순서가 곧 카드의 위에서 아래 순서다.""")
@SecurityRequirement(name = "bearerAuth")
public class OrderCardController {

  private final OrderCardService cards;

  public OrderCardController(OrderCardService cards) {
    this.cards = cards;
  }

  @Operation(
      summary = "카드 생성",
      description =
          """
          `requests` 를 **보내지 않으면** 서버가 메뉴 판정과 사용자 상용 문구로 채운다.
          보내면 그것을 그대로 쓴다 — 사용자가 화면에서 직접 고른 결과이기 때문이다.

          문구 다듬기는 ai-service 를 거치며, 실패하면 원문 그대로 나간다
          (`CardRequest.polished=false`). 카드 생성 자체는 실패하지 않는다.""")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "생성됨"),
    @ApiResponse(responseCode = "401", description = "토큰이 없거나 만료됨"),
    @ApiResponse(responseCode = "404", description = "식당 또는 메뉴가 없음")
  })
  @PostMapping
  public ResponseEntity<CardResponse> create(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody CreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(cards.create(userId, request));
  }

  @Operation(summary = "카드 조회", description = "내가 만든 카드만 볼 수 있다.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "조회됨"),
    @ApiResponse(responseCode = "401", description = "토큰이 없거나 만료됨"),
    @ApiResponse(responseCode = "404", description = "없거나 내 카드가 아님")
  })
  @GetMapping("/{id}")
  public CardResponse get(
      @AuthenticationPrincipal Long userId,
      @Parameter(description = "카드 id", example = "1") @PathVariable Long id) {
    return cards.get(userId, id);
  }
}
