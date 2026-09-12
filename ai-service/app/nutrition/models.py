"""영양성분 조회 관련 모델 (Pydantic v2)."""

from __future__ import annotations

from pydantic import BaseModel, Field

from app.nutrition.field_map import Nutrient


class NutritionFacts(BaseModel):
    """100g 기준 영양성분. 값이 ``None`` 이면 **미측정**이며 0이 아니다."""

    food_code: str | None = Field(default=None, description="식약처 FOOD_CD")
    food_name: str | None = Field(default=None, description="매칭된 FOOD_NM_KR")
    source_kind: str | None = Field(default=None, description="외식/가정식/가공식품")
    serving_size: str | None = None
    values: dict[Nutrient, float | None] = Field(default_factory=dict)

    def get(self, nutrient: Nutrient) -> float | None:
        return self.values.get(nutrient)
