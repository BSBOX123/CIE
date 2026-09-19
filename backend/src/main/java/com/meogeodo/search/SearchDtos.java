package com.meogeodo.search;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** 식당 검색·상세 응답 (SPEC 9.4). */
public final class SearchDtos {

  private SearchDtos() {}

  /** 낙관 표기. 색만으로 전달하지 않는다 (SPEC 5.2 접근성). */
  @Schema(
      description =
          """
          판정 도장. **색만으로 전달하지 말 것** — symbol 과 label 을 항상 함께 노출한다
          (색각 이상 고려). 이 객체 자체가 null 이면 아직 분석되지 않았다는 뜻이며,
          안전하다는 뜻이 **아니다.**""")
  public record Seal(
      @Schema(description = "판정값", allowableValues = {"RED", "INK", "OK"}, example = "OK")
          String verdict,
      @Schema(description = "RED=✕, INK=△, OK=○", example = "○") String symbol,
      @Schema(description = "사람이 읽는 라벨", example = "먹어도 돼요!") String label) {}

  @Schema(description = "메뉴에 붙은 태그 1건 (알레르겐 또는 주의성분)")
  public record MenuTagView(
      @Schema(description = "성분 이름", example = "밀") String value,
      @Schema(
              description =
                  """
                  알레르겐의 함량 구분. `MAIN` 은 주재료라 ✕, `TRACE` 는 간장·된장 같은
                  양념에 미량으로 들어간 것이라 △ 로 판정한다.""",
              allowableValues = {"MAIN", "TRACE"},
              example = "TRACE")
          String amount,
      @Schema(description = "이 태그가 어디서 왔는지 (LLM 태깅 / 영양성분 DB 등)", example = "LLM")
          String source,
      @Schema(description = "true 면 추정값이다. 확정된 성분표가 아니다", example = "true")
          boolean estimated) {}

  @Schema(description = "메뉴 1건. 판정은 비관적이다 — 하나라도 걸리면 위험한 쪽으로 본다")
  public record MenuView(
      @Schema(
              description =
                  "메뉴 id. 메뉴는 저장하지 않으므로 메뉴 이름에서 만든 값이다. 같은 식당의 같은 메뉴는"
                      + " 언제 불러도 같으니 주문요청카드의 menuId 로 그대로 보낼 것")
          Long id,
      @Schema(example = "초당순두부") String name,
      @Schema(
              description =
                  """
                  가격. **항상 null 이다.** 어떤 공공 API 에도 가격이 없어 싣지 않는다
                  (크롤링은 약관 위반, LLM 추정은 현장 피해). 화면에서 가격은 감출 것.""")
          Integer price,
      @Schema(description = "대표 메뉴 여부") boolean representative,
      @Schema(
              description = "태깅 상태. 미태깅이면 seal 이 비어 있다 — 안전한 것이 아니라 아직 분석 전이다",
              example = "TAGGED")
          String tagStatus,
      @Schema(description = "미태깅이거나 비로그인이면 null") Seal seal,
      @Schema(description = "이 메뉴에 붙은 전체 태그") List<MenuTagView> tags,
      @Schema(description = "내 알레르기와 겹친 **주재료** 알레르겐. 비어 있지 않으면 RED",
              example = "[\"새우\"]")
          List<String> hitMainAllergens,
      @Schema(description = "내 알레르기와 겹친 **양념 미량** 알레르겐. 이것만 있으면 INK",
              example = "[\"밀\"]")
          List<String> hitTraceAllergens,
      @Schema(description = "내 주의성분과 겹친 것", example = "[\"나트륨\"]") List<String> hitCares,
      @Schema(description = "왜 이런 판정이 나왔는지 사람이 읽는 설명") String detail,
      @Schema(description = "이 메뉴에 쓸 만한 요청 문구. 주문요청카드의 기본값이 된다",
              example = "[\"국물은 따로 주세요\"]")
          List<String> suggestedRequests) {}

  @Schema(description = "검색 결과의 식당 1건. 판정은 낙관적이다 — 안전한 메뉴가 하나라도 있으면 OK")
  public record RestaurantSummary(
      @Schema(description = "식당 id (관광공사 contentid)", example = "623223") Long id,
      @Schema(example = "초당할머니순두부") String name,
      @Schema(description = "주소·업종 등 한 줄 요약") String meta,
      BigDecimal lat,
      BigDecimal lng,
      @Schema(description = "현재 좌표로부터의 직선 거리(m)", example = "320") int distanceM,
      @Schema(description = "도보 예상 시간(분)", example = "5") int walkMinutes,
      @Schema(description = "제보 2건 이상으로 도출된 속성", example = "[\"덜짜게 해줌\"]")
          List<String> flags,
      @Schema(description = "비로그인이거나 메뉴가 전부 미태깅이면 null") Seal seal,
      @Schema(
              description =
                  "판정 근거 한 줄 요약. 메뉴가 없으면 \"메뉴 정보가 없습니다\", 이 식당의 메뉴만"
                      + " 못 불러왔으면 \"메뉴 정보를 잠시 불러오지 못했습니다\"")
          String summary,
      @Schema(description = "대표 이미지 URL") String firstImage) {}

  @Schema(description = "검색 결과")
  public record SearchResponse(
      @Schema(example = "0") int page,
      @Schema(example = "20") int size,
      @Schema(description = "반경 안의 전체 건수", example = "37") int totalCount,
      @Schema(
              description = "false 면 토큰이 없어 판정을 못 한 것이다. 이때 seal 은 전부 null",
              example = "true")
          boolean personalized,
      List<RestaurantSummary> items,
      @Schema(description = "화면에 그대로 노출해야 하는 고지 문구") String disclaimer) {}

  @Schema(description = "식당 상세. 메뉴별 판정까지 포함한다")
  public record RestaurantDetail(
      @Schema(description = "식당 id (관광공사 contentid)", example = "623223") Long id,
      @Schema(example = "초당할머니순두부") String name,
      @Schema(description = "주소·업종 등 한 줄 요약") String meta,
      BigDecimal lat,
      BigDecimal lng,
      @Schema(description = "요청에 lat,lng 를 주지 않으면 null") Integer distanceM,
      @Schema(description = "요청에 lat,lng 를 주지 않으면 null") Integer walkMinutes,
      String tel,
      String openTime,
      String restDate,
      String parking,
      String firstImage,
      @Schema(description = "제보 2건 이상으로 도출된 속성") List<String> flags,
      @Schema(description = "식당 전체 판정 (낙관적). 미태깅이면 null") Seal seal,
      @Schema(description = "false 면 토큰이 없어 판정을 못 한 것") boolean personalized,
      List<MenuView> menus,
      @Schema(description = "화면에 그대로 노출해야 하는 고지 문구") String disclaimer) {}
}
