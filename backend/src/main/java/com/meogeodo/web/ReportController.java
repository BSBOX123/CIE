package com.meogeodo.web;

import com.meogeodo.report.ReportDtos.CreateRequest;
import com.meogeodo.report.ReportDtos.FeedbackOptionsResponse;
import com.meogeodo.report.ReportDtos.ReviewListResponse;
import com.meogeodo.report.ReportDtos.ReviewView;
import com.meogeodo.report.ReportService;
import com.meogeodo.vocabulary.VocabularyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 제보 (MVP 4번). */
@RestController
@Tag(
    name = "5. 제보",
    description =
        """
        "요청이 받아들여졌는지" 를 남기는 방문 기록. 같은 제보가 **2건 이상** 모이면
        식당에 속성(flag)이 붙고 검색 필터에 쓰인다. 1건은 한 사람의 경험이라 붙이지 않는다.

        제보를 지우면 flag 도 전체 재계산되어 내려갈 수 있다.""")
public class ReportController {

  private final ReportService reports;
  private final VocabularyService vocabulary;

  public ReportController(ReportService reports, VocabularyService vocabulary) {
    this.reports = reports;
    this.vocabulary = vocabulary;
  }

  @Operation(
      summary = "제보 등록",
      description =
          """
          `feedback` 은 **정해진 문구 중에서** 고른 것만 받는다 (`GET /api/reports/options`).
          자유 문장은 `note` 에 넣을 것. flag 도출의 근거가 되는 값이라 자유 입력을 섞으면
          집계가 깨진다.

          `requests` 는 그때 실제로 사용한 요청 문구다 — 다른 사용자에게 주문 방법으로 보인다.""")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "등록됨"),
    @ApiResponse(responseCode = "401", description = "토큰이 없거나 만료됨"),
    @ApiResponse(responseCode = "404", description = "그런 식당이 없음")
  })
  @SecurityRequirement(name = "bearerAuth")
  @PostMapping("/api/reports")
  public ResponseEntity<ReviewView> create(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody CreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(reports.create(userId, request));
  }

  @Operation(
      summary = "식당의 제보 목록",
      description =
          """
          **비로그인도 볼 수 있다.** 토큰이 있으면 내가 쓴 제보에 `mine=true` 가 붙는다.

          `author` 는 `"60대 · 당뇨"` 처럼 파생한 값이다. 질환을 전부 나열하면 개인을
          좁힐 수 있어 하나만 노출한다. 나이·이름은 그대로 내보내지 않는다.

          `derivedFlags` 가 2건 이상 모여 식당에 실제로 붙은 속성이다.""")
  @SecurityRequirement(name = "bearerAuth")
  @GetMapping("/api/restaurants/{id}/reports")
  public ReviewListResponse forRestaurant(
      @AuthenticationPrincipal Long userId,
      @Parameter(description = "식당 id (관광공사 contentid)", example = "623223") @PathVariable Long id) {
    return reports.listForRestaurant(userId, id);
  }

  @Operation(summary = "내 제보 목록", description = "내가 쓴 제보 = 내 방문 기록. 최신순.")
  @ApiResponse(responseCode = "401", description = "토큰이 없거나 만료됨")
  @SecurityRequirement(name = "bearerAuth")
  @GetMapping("/api/me/reports")
  public List<ReviewView> mine(@AuthenticationPrincipal Long userId) {
    return reports.listMine(userId);
  }

  @Operation(
      summary = "내 제보 삭제",
      description = "내 제보만 지울 수 있다. 지우면 그 식당의 flag 를 다시 계산한다.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "지워짐. 본문 없음"),
    @ApiResponse(responseCode = "401", description = "토큰이 없거나 만료됨"),
    @ApiResponse(responseCode = "404", description = "없거나 내 제보가 아님")
  })
  @SecurityRequirement(name = "bearerAuth")
  @DeleteMapping("/api/me/reports/{id}")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal Long userId,
      @Parameter(description = "제보 id", example = "1") @PathVariable Long id) {
    reports.delete(userId, id);
    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "제보 문구 목록",
      description =
          """
          제보 화면에서 고를 수 있는 문구들. **비로그인도 호출할 수 있다.**

          이 목록의 문자열을 **그대로** `POST /api/reports` 의 `feedback`/`requests` 에 보낼 것.
          철자가 하나라도 다르면 집계에서 빠진다.""")
  @GetMapping("/api/reports/options")
  public FeedbackOptionsResponse options() {
    return new FeedbackOptionsResponse(
        vocabulary.feedbackPhrases(), vocabulary.requestPhrases());
  }
}
