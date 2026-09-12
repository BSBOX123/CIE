package com.meogeodo.tagging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** ai-service 와 주고받는 형태. FastAPI 쪽 Pydantic 모델과 짝을 이룬다. */
public final class AiServiceDtos {

  private AiServiceDtos() {}

  /** 태깅할 음식 1건. 영양성분은 백엔드가 보관하던 값을 함께 보낸다. */
  public record BulkDish(
      String dishId, String dishName, String foodCategory, Map<String, Double> nutrition) {}

  public record BulkTagRequest(List<BulkDish> dishes) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TagSource(String kind, BigDecimal confidence, String evidence) {}

  /** {@code amount} 는 알레르겐 태그에만 채워진다 (MAIN | TRACE). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Tag(String value, String amount, TagSource source) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DishTagResult(
      String dishName,
      List<Tag> cares,
      List<Tag> allergens,
      List<String> estimatedIngredients,
      String modelId,
      boolean needsReview,
      List<String> nutritionMissing) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BulkFailure(String dishId, String error) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BulkTagResponse(
      String modelId, List<DishTagResult> tagged, List<BulkFailure> failed) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record NutritionEntry(
      String foodCode, String foodName, String sourceKind, Map<String, Double> values) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record NutritionLookupResponse(
      int requested, int matched, Map<String, NutritionEntry> results) {}
}
