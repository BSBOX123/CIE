package com.meogeodo.tagging;

import com.meogeodo.domain.Dish;
import com.meogeodo.domain.DishRepository;
import com.meogeodo.domain.DishTag;
import com.meogeodo.domain.DishTagRepository;
import com.meogeodo.domain.TaggingRun;
import com.meogeodo.domain.TaggingRunRepository;
import com.meogeodo.tagging.AiServiceDtos.BulkDish;
import com.meogeodo.tagging.AiServiceDtos.BulkTagRequest;
import com.meogeodo.tagging.AiServiceDtos.BulkTagResponse;
import com.meogeodo.tagging.AiServiceDtos.DishTagResult;
import com.meogeodo.tagging.AiServiceDtos.NutritionEntry;
import com.meogeodo.tagging.AiServiceDtos.NutritionLookupResponse;
import com.meogeodo.tagging.AiServiceDtos.Tag;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 태깅 실행 (SPEC 7.1 [5]~[7]).
 *
 * <p>Google AI Studio 무료 티어에는 Batch API가 없어 동기 방식으로 돈다
 * (2026-09 확인). 무료 티어는 일일 요청 수도 제한하므로 <b>한 번에 전부
 * 처리하지 않고</b> 정해진 개수만 처리한다. 남은 음식은 다음 실행이 가져간다.
 *
 * <p>재개 지점은 별도로 관리하지 않는다. {@code dish.tagged_at IS NULL} 인
 * 음식이 곧 남은 작업이므로, 며칠에 걸쳐 나눠 돌아도 자연스럽게 이어진다.
 */
@Service
public class TaggingService {

  private static final Logger log = LoggerFactory.getLogger(TaggingService.class);

  /** 이 값 미만이면 검수 큐로 보낸다 (SPEC 7.1 [7] AUDIT). */
  private static final BigDecimal REVIEW_THRESHOLD = new BigDecimal("0.6");

  private final AiServiceClient aiService;
  private final DishRepository dishes;
  private final DishTagRepository dishTags;
  private final TaggingRunRepository runs;
  private final int chunkSize;

  public TaggingService(
      AiServiceClient aiService,
      DishRepository dishes,
      DishTagRepository dishTags,
      TaggingRunRepository runs,
      @Value("${meogeodo.tagging.chunk-size:50}") int chunkSize) {
    this.aiService = aiService;
    this.dishes = dishes;
    this.dishTags = dishTags;
    this.runs = runs;
    this.chunkSize = chunkSize;
  }

  /**
   * 미태깅 음식을 한 묶음 처리한다.
   *
   * @return 이번 실행에서 태깅에 성공한 건수
   */
  @Transactional
  public int tagPending() {
    List<Dish> pending = dishes.findTop200ByTaggedAtIsNull();
    if (pending.isEmpty()) {
      log.info("태깅 대상 없음");
      return 0;
    }
    if (pending.size() > chunkSize) {
      pending = pending.subList(0, chunkSize);
    }

    TaggingRun run = runs.save(new TaggingRun(pending.size()));
    lookupAndStoreNutrition(pending);

    List<BulkDish> payload = new ArrayList<>();
    Map<String, Dish> byName = new LinkedHashMap<>();
    for (Dish dish : pending) {
      payload.add(
          new BulkDish(
              String.valueOf(dish.getId()),
              dish.getNormalizedName(),
              dish.getFoodCategory(),
              dish.getNutrition()));
      byName.put(dish.getNormalizedName(), dish);
    }

    BulkTagResponse response;
    try {
      response = aiService.tagDishes(new BulkTagRequest(payload));
    } catch (RuntimeException e) {
      log.error("태깅 호출 실패", e);
      run.fail(e.getMessage());
      runs.save(run);
      return 0;
    }

    int tagged = 0;
    for (DishTagResult result : safe(response.tagged())) {
      Dish dish = byName.get(result.dishName());
      if (dish == null) {
        log.warn("요청하지 않은 음식이 결과에 있습니다: {}", result.dishName());
        continue;
      }
      applyTags(dish, result, response.modelId());
      tagged++;
    }

    int failed = safe(response.failed()).size();
    if (failed > 0) {
      // 실패한 음식은 tagged_at 이 비어 있어 다음 실행이 다시 가져간다.
      log.warn("태깅 실패 {}건 — 다음 실행에서 재시도됩니다", failed);
    }
    run.finish(tagged, failed, response.modelId());
    runs.save(run);
    log.info("태깅 완료: 성공 {}건 / 실패 {}건 / 남은 대상 있음 여부는 다음 실행에서 확인",
        tagged, failed);
    return tagged;
  }

  /**
   * 영양성분을 조회해 {@code dish} 에 저장한다.
   *
   * <p>매칭 실패는 오류가 아니다. 영양성분DB에 없는 음식은 LLM 추정만으로
   * 태깅하고 검수 대상이 된다 (SPEC 6.4-a).
   */
  private void lookupAndStoreNutrition(List<Dish> pending) {
    List<String> names = pending.stream().map(Dish::getNormalizedName).toList();
    NutritionLookupResponse lookup;
    try {
      lookup = aiService.lookupNutrition(names);
    } catch (RuntimeException e) {
      // 영양성분 없이도 태깅은 진행한다. 판정 근거가 약해질 뿐이다.
      log.warn("영양성분 조회 실패, LLM 추정만으로 진행합니다: {}", e.getMessage());
      return;
    }
    if (lookup == null || lookup.results() == null) {
      return;
    }
    for (Dish dish : pending) {
      NutritionEntry entry = lookup.results().get(dish.getNormalizedName());
      if (entry == null) {
        continue;
      }
      dish.setNutrition(entry.values());
      dish.setNutritionFoodCd(entry.foodCode());
      dishes.save(dish);
    }
    log.info("영양성분 매칭 {}/{}", lookup.matched(), lookup.requested());
  }

  /** 태그를 갈아끼운다. 재태깅 시 옛 태그가 남지 않도록 지우고 다시 넣는다. */
  private void applyTags(Dish dish, DishTagResult result, String modelId) {
    dishTags.deleteByDishId(dish.getId());

    boolean lowConfidence = false;
    for (Tag tag : safe(result.cares())) {
      lowConfidence |= saveTag(dish, DishTag.TagType.CARE, tag, modelId);
    }
    for (Tag tag : safe(result.allergens())) {
      lowConfidence |= saveTag(dish, DishTag.TagType.ALLERGEN, tag, modelId);
    }

    dish.setTaggedAt(OffsetDateTime.now());
    dish.setModelId(modelId);
    dish.setNeedsReview(result.needsReview() || lowConfidence);
    dishes.save(dish);
  }

  /** @return 신뢰도가 임계값 미만이면 true */
  private boolean saveTag(Dish dish, DishTag.TagType type, Tag tag, String modelId) {
    BigDecimal confidence =
        tag.source() == null || tag.source().confidence() == null
            ? BigDecimal.ZERO
            : tag.source().confidence();
    DishTag.Source source = parseSource(tag.source() == null ? null : tag.source().kind());

    DishTag entity = new DishTag(dish, type, tag.value(), source, confidence);
    entity.setAmount(parseAmount(type, tag.amount()));
    entity.setEvidence(tag.source() == null ? null : tag.source().evidence());
    entity.setModelId(modelId);
    dishTags.save(entity);

    return confidence.compareTo(REVIEW_THRESHOLD) < 0;
  }

  /**
   * 알레르겐 함유 정도를 읽는다.
   *
   * <p>값이 없거나 모르는 값이면 {@code MAIN} 으로 본다. 양념 수준이라고
   * 잘못 낮춰 잡는 것보다 과하게 경고하는 편이 안전하다.
   */
  private static DishTag.Amount parseAmount(DishTag.TagType type, String amount) {
    if (type != DishTag.TagType.ALLERGEN) {
      return null;
    }
    if (amount == null) {
      return DishTag.Amount.MAIN;
    }
    try {
      return DishTag.Amount.valueOf(amount);
    } catch (IllegalArgumentException e) {
      return DishTag.Amount.MAIN;
    }
  }

  private static DishTag.Source parseSource(String kind) {
    if (kind == null) {
      return DishTag.Source.LLM;
    }
    try {
      return DishTag.Source.valueOf(kind);
    } catch (IllegalArgumentException e) {
      // 모르는 출처를 신뢰도 높은 쪽으로 넣으면 안 된다. 가장 낮은 등급으로.
      return DishTag.Source.LLM;
    }
  }

  private static <T> List<T> safe(List<T> list) {
    return list == null ? List.of() : list;
  }
}
