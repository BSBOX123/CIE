"""태깅 오케스트레이션.

영양성분DB(결정론적) 결과를 우선하고, 그것으로 답할 수 없는 부분만 LLM에
맡긴다 (SPEC 7.2 우선순위).
"""

from __future__ import annotations

from app.nutrition.field_map import Nutrient
from app.tagging.models import (
    AllergenTag,
    CareTag,
    DishTagRequest,
    DishTagResponse,
    LlmDishAnalysis,
    TagSource,
)
from app.tagging.models import AllergenAmount
from app.tagging.rules import Determination, evaluate
from app.tagging.vocabulary import CareType

#: 이 값 미만이면 검수 큐로 보낸다 (SPEC 7.1 [7] AUDIT).
#:
#: 영양성분 미측정은 검수 사유로 삼지 않는다. 실측에서 매칭률이 47%라 미측정을
#: 사유로 넣으면 29건 중 27건이 검수 대상이 되어 큐가 무의미해졌다. 미측정은
#: 별도 필드(``nutrition_missing``)로 알리고, 검수는 신뢰도 낮은 태그에만 건다.
REVIEW_THRESHOLD = 0.6

#: 퓨린은 영양성분DB에 항목 자체가 없어 LLM 추정만 가능하다.
#: 근거가 약하므로 신뢰도에 상한을 둔다 (SPEC 6.4-c).
PURINE_CONFIDENCE_CAP = 0.5


def _nutrition_care_tags(
    nutrition: dict[Nutrient, float | None], food_category: str | None
) -> list[CareTag]:
    """수치로 확정된 주의성분. 근거가 명확하므로 신뢰도 1.0."""
    return [
        CareTag(
            value=d.care,
            source=TagSource(kind="NUTRITION_DB", confidence=1.0, evidence=d.evidence),
        )
        for d in evaluate(nutrition, food_category)
        if d.result is Determination.FLAGGED
    ]


def _unmeasured(
    nutrition: dict[Nutrient, float | None], food_category: str | None
) -> set[CareType]:
    return {
        d.care
        for d in evaluate(nutrition, food_category)
        if d.result is Determination.UNKNOWN
    }


def combine(
    request: DishTagRequest,
    analysis: LlmDishAnalysis,
    model_id: str,
) -> DishTagResponse:
    """영양성분 판정 + LLM 분석을 하나의 태그 집합으로 합친다."""
    nutrition = request.nutrition or {}
    cares = _nutrition_care_tags(nutrition, request.food_category)
    tagged = {tag.value for tag in cares}

    # LLM 전담 2종 (SPEC 6.4-c)
    if analysis.is_refined_carb_staple:
        cares.append(
            CareTag(
                value=CareType.REFINED_CARB,
                source=TagSource(
                    kind="LLM",
                    confidence=analysis.confidence,
                    evidence="흰밥·면·빵이 주가 되는 음식으로 분류됨",
                ),
            )
        )
    if analysis.is_high_purine:
        cares.append(
            CareTag(
                value=CareType.PURINE,
                source=TagSource(
                    kind="LLM",
                    confidence=min(analysis.confidence, PURINE_CONFIDENCE_CAP),
                    evidence=analysis.purine_reasoning,
                ),
            )
        )

    # 영양성분DB에서 미측정이라 판정하지 못한 성분은 LLM 재료 추정으로 보완할 수
    # 없다(재료 목록만으로 함량을 알 수 없다). 태그하지 않고 검수 대상으로 남긴다.
    unmeasured = _unmeasured(nutrition, request.food_category) - tagged

    # 같은 알레르겐이 여러 번 오면 더 강한 쪽(MAIN)을 남긴다.
    strongest: dict[str, AllergenTag] = {}
    for finding in analysis.allergens:
        tag = AllergenTag(
            value=finding.allergen,
            amount=finding.amount,
            source=TagSource(
                kind="LLM",
                confidence=analysis.confidence,
                evidence=finding.reason,
            ),
        )
        existing = strongest.get(finding.allergen.value)
        if existing is None or (
            existing.amount is AllergenAmount.TRACE
            and tag.amount is AllergenAmount.MAIN
        ):
            strongest[finding.allergen.value] = tag
    allergens = list(strongest.values())

    low_confidence = any(
        tag.source.confidence < REVIEW_THRESHOLD for tag in (*cares, *allergens)
    )
    return DishTagResponse(
        dish_name=request.dish_name,
        cares=cares,
        allergens=allergens,
        estimated_ingredients=analysis.ingredients,
        model_id=model_id,
        needs_review=low_confidence,
        nutrition_missing=sorted(c.value for c in unmeasured),
    )
