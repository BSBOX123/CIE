package com.meogeodo.web;

import com.meogeodo.food.FoodDtos.FoodDetail;
import com.meogeodo.food.FoodDtos.FoodListResponse;
import com.meogeodo.food.LocalFoodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 지역 음식 (SPEC 9.3). */
@RestController
@RequestMapping("/api/foods")
@Validated
@Tag(
    name = "6. 지역 음식",
    description =
        """
        현재 GPS 좌표의 지역 음식과, 그 음식을 파는 근처 식당.

        **좌표는 프론트가 브라우저에서 얻어 보낸다** (`navigator.geolocation`). 서버는
        좌표에서 가장 가까운 관광공사 등록 식당의 시도를 이어받아 지역을 정한다 (SPEC 1.1.1).
        위치 권한이 거부되면 사용자가 고른 지역의 중심 좌표를 보내면 된다.

        현재 보유 지역은 **경상권(부산·대구·울산·경북·경남)** 이다. 그 밖의 지역은
        `items` 가 빈 배열이며, 이때는 지역 음식 섹션을 숨기고 근처 식당만 보여 줄 것.

        토큰은 선택이다. 있으면 음식마다 사용자 기준 판정이 붙는다.""")
@SecurityRequirement(name = "bearerAuth")
public class FoodController {

  private final LocalFoodService foods;

  public FoodController(LocalFoodService foods) {
    this.foods = foods;
  }

  @Operation(
      summary = "현재 위치의 지역 음식",
      description =
          """
          내 현재 시도의 지역 음식만 온다. 응답의 `region`·`district` 로 "부산 해운대구" 같은
          현재 위치 표시를 그릴 수 있다.""")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "조회됨. 보유하지 않은 지역이면 items 가 빈 배열"),
    @ApiResponse(responseCode = "503", description = "관광공사 API 를 잠시 불러오지 못해 지역을 정하지 못함")
  })
  @GetMapping
  public FoodListResponse list(
      @AuthenticationPrincipal Long userId,
      @Parameter(description = "위도", required = true, example = "35.1587")
          @RequestParam @Min(-90) @Max(90) double lat,
      @Parameter(description = "경도", required = true, example = "129.1604")
          @RequestParam @Min(-180) @Max(180) double lng) {
    return foods.list(userId, lat, lng);
  }

  @Operation(
      summary = "지역 음식 상세",
      description =
          """
          설명·판정·요청 팁과 이 음식을 파는 식당(최대 10곳, 가까운 순)을 준다.
          식당은 **내 현재 시도 안에서만** 관광공사에서 실시간으로 찾으므로 좌표(`lat`,`lng`)를
          함께 줘야 채워진다.
          관광공사를 잠시 못 부르면 `restaurantsUnavailable=true` 로 식당만 비운다.""")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "조회됨"),
    @ApiResponse(responseCode = "404", description = "그런 지역 음식이 없음")
  })
  @GetMapping("/{foodId}")
  public FoodDetail detail(
      @AuthenticationPrincipal Long userId,
      @Parameter(description = "지역 음식 id", example = "milmyeon") @PathVariable String foodId,
      @Parameter(description = "현재 위도", example = "35.1587") @RequestParam(required = false)
          Double lat,
      @Parameter(description = "현재 경도", example = "129.1604") @RequestParam(required = false)
          Double lng) {
    return foods.detail(userId, foodId, lat, lng);
  }
}
