package com.meogeodo.tagging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meogeodo.domain.Dish;
import com.meogeodo.domain.DishRepository;
import com.meogeodo.domain.DishTag;
import com.meogeodo.domain.DishTagRepository;
import com.meogeodo.domain.TaggingRunRepository;
import com.meogeodo.tagging.AiServiceDtos.BulkFailure;
import com.meogeodo.tagging.AiServiceDtos.BulkTagResponse;
import com.meogeodo.tagging.AiServiceDtos.DishTagResult;
import com.meogeodo.tagging.AiServiceDtos.NutritionEntry;
import com.meogeodo.tagging.AiServiceDtos.NutritionLookupResponse;
import com.meogeodo.tagging.AiServiceDtos.Tag;
import com.meogeodo.tagging.AiServiceDtos.TagSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** 태깅 실행 테스트. ai-service 호출만 대역으로 바꾼다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaggingServiceTest {

  @Autowired private TaggingService tagging;
  @Autowired private DishRepository dishes;
  @Autowired private DishTagRepository dishTags;
  @Autowired private TaggingRunRepository runs;

  @MockitoBean private AiServiceClient aiService;

  /** 주의성분 태그 (amount 없음). */
  private static Tag care(String value, String kind, String confidence, String evidence) {
    return new Tag(value, null, new TagSource(kind, new BigDecimal(confidence), evidence));
  }

  /** 알레르겐 태그. amount 는 MAIN 또는 TRACE. */
  private static Tag allergen(String value, String amount, String confidence) {
    return new Tag(value, amount,
        new TagSource("LLM", new BigDecimal(confidence), value + " 근거"));
  }

  private Dish givenDish(String name) {
    return dishes.save(new Dish(name));
  }

  private void givenNoNutrition() {
    when(aiService.lookupNutrition(any()))
        .thenReturn(new NutritionLookupResponse(1, 0, Map.of()));
  }

  /** 결과 1건. 인자가 늘어날 때 테스트 전체가 깨지지 않도록 여기서만 만든다. */
  private static DishTagResult result(
      String dishName, List<Tag> cares, List<Tag> allergens, boolean needsReview) {
    return new DishTagResult(
        dishName, cares, allergens, List.of(), "gemini-3.8-flash", needsReview, List.of());
  }

  private void givenTagResult(DishTagResult... results) {
    when(aiService.tagDishes(any()))
        .thenReturn(new BulkTagResponse("gemini-3.8-flash", List.of(results), List.of()));
  }

  // ── 정상 처리 ─────────────────────────────────────────────────────

  @Test
  @DisplayName("미태깅 음식을 태깅하고 실행 기록을 남긴다")
  void tagsPending() {
    var dish = givenDish("김치찌개");
    givenNoNutrition();
    givenTagResult(result("김치찌개", List.of(care("나트륨", "NUTRITION_DB", "1.0", "나트륨 1473mg")), List.of(allergen("돼지고기", "MAIN", "0.85")), false));

    assertThat(tagging.tagPending()).isEqualTo(1);

    assertThat(dishTags.findByDishId(dish.getId())).hasSize(2);
    assertThat(dishes.findById(dish.getId()).orElseThrow().isTagged()).isTrue();
    assertThat(runs.findAll()).singleElement()
        .satisfies(r -> assertThat(r.getTagged()).isEqualTo(1));
  }

  @Test
  @DisplayName("대상이 없으면 호출하지 않는다")
  void nothingToTag() {
    assertThat(tagging.tagPending()).isZero();
    verify(aiService, never()).tagDishes(any());
  }

  @Test
  @DisplayName("조회한 영양성분을 dish에 저장해 함께 보낸다")
  void storesNutrition() {
    var dish = givenDish("김치찌개");
    when(aiService.lookupNutrition(any()))
        .thenReturn(new NutritionLookupResponse(1, 1,
            Map.of("김치찌개", new NutritionEntry("CD1", "김치찌개", "외식(분석 함량)",
                Map.of("나트륨", 491.0)))));
    givenTagResult(result("김치찌개", List.of(), List.of(), false));

    tagging.tagPending();

    assertThat(dishes.findById(dish.getId()).orElseThrow().getNutrition())
        .containsEntry("나트륨", 491.0);
  }

  @Test
  @DisplayName("영양성분 조회가 실패해도 태깅은 진행한다")
  void survivesNutritionFailure() {
    givenDish("김치찌개");
    when(aiService.lookupNutrition(any())).thenThrow(new RuntimeException("연결 실패"));
    givenTagResult(result("김치찌개", List.of(), List.of(), false));

    assertThat(tagging.tagPending()).isEqualTo(1);
  }

  // ── 태그 출처 ─────────────────────────────────────────────────────

  @Test
  @DisplayName("수치 근거 태그와 LLM 추정 태그를 구분해 저장한다")
  void distinguishesSources() {
    var dish = givenDish("김치찌개");
    givenNoNutrition();
    givenTagResult(result("김치찌개", List.of(care("나트륨", "NUTRITION_DB", "1.0", "수치 근거"),
            care("퓨린", "LLM", "0.5", "육수가 진합니다")), List.of(), false));

    tagging.tagPending();

    var bySource = dishTags.findByDishId(dish.getId()).stream()
        .collect(Collectors.toMap(DishTag::getTagValue, DishTag::getSource));
    assertThat(bySource.get("나트륨")).isEqualTo(DishTag.Source.NUTRITION_DB);
    assertThat(bySource.get("퓨린")).isEqualTo(DishTag.Source.LLM);
  }

  @Test
  @DisplayName("모르는 출처는 가장 낮은 등급으로 저장한다")
  void unknownSourceDowngrades() {
    var dish = givenDish("김치찌개");
    givenNoNutrition();
    givenTagResult(result("김치찌개", List.of(care("나트륨", "정체불명", "1.0", "?")), List.of(), false));

    tagging.tagPending();

    assertThat(dishTags.findByDishId(dish.getId()).get(0).getSource())
        .isEqualTo(DishTag.Source.LLM);
  }

  @Test
  @DisplayName("낮은 신뢰도 태그가 있으면 검수 대상이 된다")
  void marksLowConfidenceForReview() {
    var dish = givenDish("듣도보도못한음식");
    givenNoNutrition();
    givenTagResult(result("듣도보도못한음식", List.of(), List.of(allergen("대두", "TRACE", "0.3")), false));

    tagging.tagPending();

    assertThat(dishes.findById(dish.getId()).orElseThrow().isNeedsReview()).isTrue();
  }

  @Test
  @DisplayName("재태깅하면 옛 태그가 남지 않는다")
  void replacesOldTags() {
    var dish = givenDish("김치찌개");
    dishTags.save(new DishTag(dish, DishTag.TagType.CARE, "칼륨",
        DishTag.Source.LLM, new BigDecimal("0.5")));
    givenNoNutrition();
    givenTagResult(result("김치찌개", List.of(care("나트륨", "NUTRITION_DB", "1.0", "근거")), List.of(), false));

    tagging.tagPending();

    assertThat(dishTags.findByDishId(dish.getId()))
        .extracting(DishTag::getTagValue).containsExactly("나트륨");
  }

  // ── 실패와 재시도 ─────────────────────────────────────────────────

  @Test
  @DisplayName("실패한 음식은 태깅되지 않아 다음 실행이 다시 가져간다")
  void failedDishesAreRetried() {
    var dish = givenDish("김치찌개");
    givenNoNutrition();
    when(aiService.tagDishes(any()))
        .thenReturn(new BulkTagResponse("gemini-3.8-flash", List.of(),
            List.of(new BulkFailure(String.valueOf(dish.getId()), "429 rate limit"))));

    tagging.tagPending();

    assertThat(dishes.findById(dish.getId()).orElseThrow().isTagged()).isFalse();
    assertThat(dishes.findTop200ByTaggedAtIsNull())
        .extracting(Dish::getNormalizedName).contains("김치찌개");
    assertThat(runs.findAll()).singleElement()
        .satisfies(r -> assertThat(r.getFailed()).isEqualTo(1));
  }

  @Test
  @DisplayName("일부만 성공해도 성공분은 저장된다")
  void partialSuccessIsKept() {
    var ok = givenDish("김치찌개");
    var bad = givenDish("물회");
    givenNoNutrition();
    when(aiService.tagDishes(any()))
        .thenReturn(new BulkTagResponse("gemini-3.8-flash",
            List.of(result("김치찌개", List.of(care("나트륨", "NUTRITION_DB", "1.0", "근거")), List.of(), false)),
            List.of(new BulkFailure(String.valueOf(bad.getId()), "timeout"))));

    assertThat(tagging.tagPending()).isEqualTo(1);

    assertThat(dishes.findById(ok.getId()).orElseThrow().isTagged()).isTrue();
    assertThat(dishes.findById(bad.getId()).orElseThrow().isTagged()).isFalse();
  }

  @Test
  @DisplayName("호출 자체가 실패하면 실행을 실패로 기록하고 아무것도 태깅하지 않는다")
  void recordsFailedRun() {
    var dish = givenDish("김치찌개");
    givenNoNutrition();
    when(aiService.tagDishes(any())).thenThrow(new RuntimeException("503"));

    assertThat(tagging.tagPending()).isZero();

    assertThat(dishes.findById(dish.getId()).orElseThrow().isTagged()).isFalse();
    assertThat(runs.findAll()).singleElement()
        .satisfies(r -> assertThat(r.getNote()).contains("503"));
  }

  @Test
  @DisplayName("요청하지 않은 음식이 결과에 있으면 무시한다")
  void ignoresUnrequestedResults() {
    givenDish("김치찌개");
    givenNoNutrition();
    givenTagResult(result("듣도보도못한음식", List.of(), List.of(), false));

    assertThat(tagging.tagPending()).isZero();
  }

  @Test
  @DisplayName("출처 우선순위는 SPEC 7.2를 따른다")
  void sourceRanking() {
    assertThat(DishTag.Source.ADMIN_VERIFIED.rank())
        .isGreaterThan(DishTag.Source.NUTRITION_DB.rank());
    assertThat(DishTag.Source.NUTRITION_DB.rank())
        .isGreaterThan(DishTag.Source.COMMUNITY.rank());
    assertThat(DishTag.Source.COMMUNITY.rank()).isGreaterThan(DishTag.Source.LLM.rank());
  }
}
