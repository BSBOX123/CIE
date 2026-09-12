"""태깅 요청/응답 DTO (Pydantic v2).

SPEC 8.5. ``dish`` 단위로 태깅하므로 식당 정보는 받지 않는다 — 같은 음식은
어디서나 같은 판정이 나와야 한다(SPEC 4.2 dish 정규화).
"""

from __future__ import annotations

from pydantic import BaseModel, Field

from enum import StrEnum

from app.nutrition.field_map import Nutrient
from app.tagging.vocabulary import Allergen, CareType


class AllergenAmount(StrEnum):
    """알레르겐이 음식에 들어가는 정도.

    간장·된장이 들어간다는 이유로 거의 모든 한국 음식에 `대두`·`밀`이 붙으면
    밀 알레르기 사용자에게 대부분의 메뉴가 ✕가 되어 서비스가 쓸모없어진다.
    실측에서 강릉 메뉴 29건 중 19건이 그렇게 나왔다.

    그렇다고 양념 수준을 빼버리면 실제로 위험하다. 그래서 없애는 대신
    **두 단계로 나눠 사용자가 판단하게** 한다.
    """

    #: 주재료. 빼면 그 음식이 아니게 된다. (물회의 새우, 소금빵의 우유)
    MAIN = "MAIN"
    #: 양념·부재료에 미량. 빼달라고 요청할 여지가 있다. (간장의 밀, 된장의 대두)
    TRACE = "TRACE"


class TagSource(BaseModel):
    """태그 출처. SPEC 7.2 우선순위와 대응."""

    kind: str = Field(description="NUTRITION_DB | LLM | ADMIN_VERIFIED | COMMUNITY")
    confidence: float = Field(ge=0.0, le=1.0)
    evidence: str


class CareTag(BaseModel):
    value: CareType
    source: TagSource


class AllergenTag(BaseModel):
    value: Allergen
    amount: AllergenAmount
    source: TagSource


class DishTagRequest(BaseModel):
    """정규화된 음식명 1건에 대한 태깅 요청."""

    dish_name: str = Field(min_length=1, max_length=100)
    food_category: str | None = Field(
        default=None, description="식약처 FOOD_CAT1_NM. 1회 섭취량 결정에 사용"
    )
    nutrition: dict[Nutrient, float | None] | None = None


class DishTagResponse(BaseModel):
    """태깅 결과. 판정(RED/INK/OK)은 여기서 내리지 않는다 — SPEC 8.2."""

    dish_name: str
    cares: list[CareTag] = Field(default_factory=list)
    allergens: list[AllergenTag] = Field(default_factory=list)
    estimated_ingredients: list[str] = Field(default_factory=list)
    model_id: str | None = None
    needs_review: bool = Field(
        default=False, description="confidence 낮은 태그가 있어 검수 큐 대상"
    )
    nutrition_missing: list[str] = Field(
        default_factory=list,
        description=(
            "영양성분DB에 수치가 없어 판정하지 못한 주의성분. "
            "검수 사유는 아니지만 근거가 약하다는 표시"
        ),
    )


class AllergenFinding(BaseModel):
    """알레르겐 1건과 그 정도."""

    allergen: Allergen
    amount: AllergenAmount = Field(
        description=(
            "MAIN=주재료로 들어감(빼면 그 음식이 아님). "
            "TRACE=양념·부재료에 미량(간장의 밀, 된장의 대두처럼 빼달라고 요청할 여지가 있음)"
        )
    )
    reason: str = Field(description="어디에 들어가는지 짧게")


class LlmDishAnalysis(BaseModel):
    """LLM이 구조화 출력으로 돌려주는 형태.

    LLM은 '이 음식에 무엇이 들어가는가'만 답한다. 임계값 비교나 최종 판정은
    하지 않는다.
    """

    ingredients: list[str] = Field(
        description="이 음식에 일반적으로 들어가는 주요 재료"
    )
    allergens: list[AllergenFinding] = Field(
        description="식약처 표시 대상 19종 중 이 음식에 들어갈 가능성이 있는 것과 그 정도"
    )
    is_refined_carb_staple: bool = Field(
        description="흰밥·면·빵 등 정제 탄수화물이 주가 되는 음식인지"
    )
    is_high_purine: bool = Field(
        description="진한 육수·내장·등푸른생선·조개류 등 퓨린이 높은 음식인지"
    )
    purine_reasoning: str = Field(description="퓨린 판단 근거 한두 문장")
    confidence: float = Field(
        ge=0.0, le=1.0, description="이 음식의 재료 구성에 대한 확신도"
    )
