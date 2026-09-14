"""영양성분 조회 관련 모델 (Pydantic v2)."""

from __future__ import annotations

from pydantic import BaseModel, Field

from app.nutrition.field_map import Nutrient


class NutritionFacts(BaseModel):
    """100g 기준 영양성분. 값이 ``None`` 이면 **미측정**이며 0이 아니다."""

    food_code: str | None = Field(default=None, description="식약처 FOOD_CD")
    food_name: str | None = Field(default=None, description="매칭된 FOOD_NM_KR")
    source_kind: str | None = Field(default=None, description="외식/가정식/가공식품")
    #: 식약처 FOOD_CAT1_NM. 1회 섭취 중량을 정하는 데 쓴다(portion.PORTION_GRAMS).
    #: 이 값이 없으면 모든 음식이 기본값 150g으로 계산돼, 국·탕·면처럼 실제로
    #: 300~400g을 먹는 음식의 나트륨이 크게 과소평가된다.
    food_category: str | None = Field(default=None, description="식약처 FOOD_CAT1_NM")
    serving_size: str | None = None
    values: dict[Nutrient, float | None] = Field(default_factory=dict)

    def get(self, nutrient: Nutrient) -> float | None:
        return self.values.get(nutrient)
