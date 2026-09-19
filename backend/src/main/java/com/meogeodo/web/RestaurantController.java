package com.meogeodo.web;

import com.meogeodo.search.RestaurantSearchService;
import com.meogeodo.search.SearchDtos.RestaurantDetail;
import com.meogeodo.search.SearchDtos.SearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GPS 반경 식당 검색 (SPEC 9.4, MVP 2번).
 *
 * <p>비로그인도 호출할 수 있다. 그때는 목록만 나오고 판정은 비어 있으며
 * {@code personalized=false} 로 알린다.
 */
@RestController
@RequestMapping("/api/restaurants")
@Validated
@Tag(
    name = "3. 식당",
    description =
        """
        GPS 반경 검색과 식당 상세.

        식당·메뉴는 요청마다 **한국관광공사 API 에서 실시간으로** 받는다. 식당 `id` 는
        관광공사 `contentid` 다. 관광공사를 불러오지 못하면 빈 결과가 아니라 `503`
        (`TOUR_API_UNAVAILABLE`) 으로 답한다 — "근처에 식당이 없다"와 구분할 것.

        **토큰은 선택이다.** 없으면 목록만 나오고 판정(`seal`)은 비어 있으며
        `personalized=false` 로 알린다. 있으면 같은 경로가 사용자 기준 판정을 함께 준다.
        Swagger UI 에서 판정을 보려면 위쪽 Authorize 로 토큰을 넣고 호출할 것.""")
@SecurityRequirement(name = "bearerAuth")
public class RestaurantController {

  private final RestaurantSearchService search;

  public RestaurantController(RestaurantSearchService search) {
    this.search = search;
  }

  @Operation(
      summary = "GPS 반경 검색",
      description =
          """
          현재 좌표에서 반경 안의 식당을 가까운 순으로 돌려준다.

          식당 판정은 **낙관적**이다 — 안전한 메뉴가 하나라도 있으면 `OK`,
          하나도 없고 조절 가능한 것이 있으면 `INK`, 전부 걸리면 `RED`.

          `seal` 이 `null` 이면 **아직 분석되지 않은 식당**이다. 안전하다는 뜻이 아니므로
          배지를 그리지 말 것.""")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "검색됨. 결과가 없으면 items 가 빈 배열"),
    @ApiResponse(responseCode = "400", description = "좌표 범위를 벗어남"),
    @ApiResponse(responseCode = "503", description = "관광공사 API 를 잠시 불러오지 못함. 잠시 후 다시 시도")
  })
  @GetMapping
  public SearchResponse search(
      @AuthenticationPrincipal Long userId,
      @Parameter(description = "위도", required = true, example = "37.7519")
          @RequestParam @Min(-90) @Max(90) double lat,
      @Parameter(description = "경도", required = true, example = "128.8761")
          @RequestParam @Min(-180) @Max(180) double lng,
      @Parameter(description = "반경(m). 비우면 서버 기본값", example = "1000")
          @RequestParam(required = false) Integer radius,
      @Parameter(description = "식당 이름 검색어", example = "초당순두부")
          @RequestParam(required = false) String q,
      @Parameter(description = "제보에서 도출된 속성으로 거르기. 여러 개면 모두 만족하는 곳만")
          @RequestParam(required = false) List<String> flags,
      @Parameter(description = "0부터 시작", example = "0") @RequestParam(defaultValue = "0") int page,
      @Parameter(description = "한 쪽 개수. 0 이면 서버 기본값", example = "20")
          @RequestParam(defaultValue = "0") int size) {
    return search.search(userId, lat, lng, radius, q, flags, page, size);
  }

  @Operation(
      summary = "식당 상세",
      description =
          """
          메뉴별 판정까지 함께 온다. 메뉴 판정은 **비관적**이다 — 주재료 알레르겐이
          내 알레르기와 겹치면 `RED`, 양념에 미량으로 들어간 것(`hitTraceAllergens`)이나
          주의성분이 걸리면 `INK`.

          메뉴의 `tagStatus` 가 미태깅이면 `seal` 이 비어 있다. 그 메뉴는 판정하지 않은
          것이지 안전한 것이 아니다.

          좌표(`lat`,`lng`)를 함께 주면 거리·도보 시간도 채워 준다. 안 주면 `null`.""")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "조회됨"),
    @ApiResponse(responseCode = "404", description = "그런 식당이 없음"),
    @ApiResponse(responseCode = "503", description = "관광공사 API 를 잠시 불러오지 못함. 잠시 후 다시 시도")
  })
  @GetMapping("/{id}")
  public RestaurantDetail detail(
      @AuthenticationPrincipal Long userId,
      @Parameter(description = "식당 id (관광공사 contentid)", example = "2869664")
          @PathVariable Long id,
      @Parameter(description = "현재 위도. 주면 거리를 계산해 준다", example = "37.7519")
          @RequestParam(required = false) Double lat,
      @Parameter(description = "현재 경도", example = "128.8761") @RequestParam(required = false)
          Double lng) {
    return search.detail(userId, id, lat, lng);
  }
}
