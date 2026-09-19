package com.meogeodo.food;

import com.meogeodo.search.SearchDtos.RestaurantSummary;
import com.meogeodo.search.SearchDtos.Seal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 지역 음식 응답 (SPEC 9.3). */
public final class FoodDtos {

  private FoodDtos() {}

  @Schema(description = "현재 위치의 지역 음식")
  public record FoodListResponse(
      @Schema(description = "현재 위치의 시도. 근처에 관광공사 등록 식당이 없어 알 수 없으면 null",
              example = "부산")
          String region,
      @Schema(description = "현재 위치의 시군구", example = "해운대구") String district,
      @Schema(description = "법정동 시도 코드", example = "26") String regionCode,
      @Schema(
              description =
                  """
                  지역 음식. 내 시도의 음식이 먼저 오고(sameRegion=true), 이어서 같은 권역의
                  다른 지역 음식이 온다. **비어 있으면 지역 음식 섹션을 숨길 것** (SPEC 7.5).
                  현재는 경상권(부산·대구·울산·경북·경남)만 보유한다.""")
          List<FoodItem> items,
      @Schema(description = "false 면 토큰이 없어 판정을 못 한 것") boolean personalized,
      @Schema(description = "화면에 그대로 노출해야 하는 고지 문구") String disclaimer) {}

  @Schema(description = "지역 음식 1건")
  public record FoodItem(
      @Schema(example = "milmyeon") String id,
      @Schema(example = "밀면") String name,
      @Schema(description = "이 음식의 지역", example = "부산") String region,
      @Schema(description = "내 현재 시도의 음식인지") boolean sameRegion,
      @Schema(description = "TAGGED 가 아니면 분석 전이라 seal 이 비어 있다", example = "TAGGED")
          String tagStatus,
      @Schema(description = "비로그인이거나 분석 전이면 null. 안전하다는 뜻이 아니다") Seal seal,
      @Schema(description = "판정 요약 (SPEC 3.3)", example = "밀 있음") String summary) {}

  @Schema(description = "지역 음식 상세")
  public record FoodDetail(
      @Schema(example = "milmyeon") String id,
      @Schema(example = "밀면") String name,
      @Schema(example = "부산") String region,
      @Schema(example = "부산 지역 음식") String regionLine,
      String description,
      @Schema(example = "TAGGED") String tagStatus,
      @Schema(description = "비로그인이거나 분석 전이면 null") Seal seal,
      @Schema(description = "내 알레르기와 겹치는 재료가 있는지") boolean hasAllergen,
      @Schema(description = "알레르기 안내 한 줄. 겹치는 게 없으면 null",
              example = "밀 — 주재료라 뺄 수 없습니다. 다른 음식을 고르세요.")
          String allergenText,
      @Schema(description = "내 주의성분과 겹치는 것") List<CareReason> careReasons,
      @Schema(description = "주문할 때 쓸 만한 요청 문구. 주문요청카드에 담을 수 있다") List<Tip> tips,
      @Schema(
              description =
                  """
                  이 음식을 파는 근처 식당 (가까운 순, 최대 10곳). 관광공사에서 실시간으로
                  찾는다 — 가게 이름에 음식명이 들어간 곳 + 근처 식당 중 메뉴에 있는 곳.
                  요청에 lat,lng 가 없으면 빈 배열.""")
          List<RestaurantSummary> restaurants,
      @Schema(description = "true 면 관광공사를 잠시 못 불러 식당 목록이 비었다. 음식 정보는 정상")
          boolean restaurantsUnavailable,
      @Schema(description = "false 면 토큰이 없어 판정을 못 한 것") boolean personalized,
      @Schema(description = "화면에 그대로 노출해야 하는 고지 문구") String disclaimer) {}

  public record CareReason(
      @Schema(example = "나트륨") String name,
      @Schema(example = "국물∙양념∙젓갈에 몰려 있어요") String note) {}

  public record Tip(
      @Schema(example = "육수는 조금만 담아 주세요") String phrase,
      @Schema(description = "화면 초기 선택 상태") boolean selected) {}
}
