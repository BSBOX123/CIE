"""태깅 조합 로직 테스트 (SPEC 7.2 우선순위, 8.2 역할 경계)."""

from __future__ import annotations

import json
from pathlib import Path

from app.nutrition.field_map import extract
from app.tagging.models import (
    AllergenAmount,
    AllergenFinding,
    DishTagRequest,
    LlmDishAnalysis,
)
from app.tagging.service import PURINE_CONFIDENCE_CAP, combine
from app.tagging.vocabulary import Allergen, CareType

FIXTURES = Path(__file__).parent / "fixtures"


def nutrition(name: str):
    return extract(json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8")))


def analysis(**over) -> LlmDishAnalysis:
    base = dict(
        ingredients=["돼지고기", "김치", "두부"],
        allergens=[
            AllergenFinding(allergen=Allergen.PORK, amount=AllergenAmount.MAIN,
                            reason="돼지고기가 주재료입니다"),
            AllergenFinding(allergen=Allergen.SOY, amount=AllergenAmount.TRACE,
                            reason="된장으로 간을 맞춥니다"),
        ],
        is_refined_carb_staple=False,
        is_high_purine=True,
        purine_reasoning="돼지고기 육수를 진하게 냅니다.",
        confidence=0.8,
    )
    base.update(over)
    return LlmDishAnalysis(**base)


def test_영양성분_판정이_신뢰도_1로_들어간다():
    """수치 근거가 있는 태그는 LLM 추정과 구분되어야 한다."""
    req = DishTagRequest(
        dish_name="김치찌개",
        food_category="찌개 및 전골류",
        nutrition=nutrition("kimchi_jjigae"),
    )
    result = combine(req, analysis(), "claude-sonnet-5")
    sodium = next(t for t in result.cares if t.value is CareType.SODIUM)
    assert sodium.source.kind == "NUTRITION_DB"
    assert sodium.source.confidence == 1.0
    assert "1회" in sodium.source.evidence


def test_퓨린은_LLM만_판정하고_신뢰도_상한이_걸린다():
    """영양성분DB에 퓨린 항목이 없어 근거가 약하다 (SPEC 6.4-c)."""
    req = DishTagRequest(
        dish_name="김치찌개",
        food_category="찌개 및 전골류",
        nutrition=nutrition("kimchi_jjigae"),
    )
    result = combine(req, analysis(confidence=0.9), "claude-sonnet-5")
    purine = next(t for t in result.cares if t.value is CareType.PURINE)
    assert purine.source.kind == "LLM"
    assert purine.source.confidence <= PURINE_CONFIDENCE_CAP


def test_정제탄수화물은_LLM_분류를_따른다():
    req = DishTagRequest(dish_name="칼국수", food_category="면 및 만두류",
                         nutrition=nutrition("ramen"))
    result = combine(req, analysis(is_refined_carb_staple=True), "m")
    assert CareType.REFINED_CARB in {t.value for t in result.cares}


def test_알레르겐은_LLM에서만_온다():
    """영양성분DB에는 알레르기 정보가 없다."""
    req = DishTagRequest(dish_name="김치찌개", nutrition=nutrition("kimchi_jjigae"))
    result = combine(req, analysis(), "m")
    assert {t.value for t in result.allergens} == {Allergen.PORK, Allergen.SOY}
    assert all(t.source.kind == "LLM" for t in result.allergens)


def test_알레르겐_중복은_제거되고_강한_쪽이_남는다():
    """같은 알레르겐이 MAIN·TRACE 로 둘 다 오면 MAIN 이 이겨야 안전하다."""
    req = DishTagRequest(dish_name="김치찌개", nutrition=nutrition("kimchi_jjigae"))
    result = combine(req, analysis(allergens=[
        AllergenFinding(allergen=Allergen.SOY, amount=AllergenAmount.TRACE, reason="간장"),
        AllergenFinding(allergen=Allergen.SOY, amount=AllergenAmount.MAIN, reason="두부"),
    ]), "m")
    assert len(result.allergens) == 1
    assert result.allergens[0].amount is AllergenAmount.MAIN


def test_주재료와_양념_미량을_구분한다():
    """간장의 밀까지 MAIN 으로 잡으면 밀 알레르기 사용자에게 거의 모든
    한국 음식이 금지된다. 실측에서 29건 중 19건이 그랬다."""
    req = DishTagRequest(dish_name="김치찌개", nutrition=nutrition("kimchi_jjigae"))
    result = combine(req, analysis(), "m")
    by_value = {t.value: t.amount for t in result.allergens}
    assert by_value[Allergen.PORK] is AllergenAmount.MAIN
    assert by_value[Allergen.SOY] is AllergenAmount.TRACE


def test_신뢰도가_낮으면_검수_대상이_된다():
    req = DishTagRequest(dish_name="물회", nutrition=nutrition("kimchi_jjigae"))
    result = combine(req, analysis(confidence=0.3), "m")
    assert result.needs_review is True


def test_미측정_성분은_검수가_아니라_별도_필드로_알린다():
    """매칭률이 47%라 미측정을 검수 사유로 넣으면 29건 중 27건이 검수 대상이
    되어 큐가 무의미해진다. 근거가 약하다는 사실은 따로 알린다."""
    req = DishTagRequest(dish_name="백미밥", food_category="밥류",
                         nutrition=nutrition("rice"))
    result = combine(req, analysis(confidence=0.95, is_high_purine=False), "m")
    assert result.needs_review is False
    assert "칼륨" in result.nutrition_missing


def test_미측정_성분을_태그로_만들지_않는다():
    """재료 목록만으로는 함량을 알 수 없으므로 추측해서 태그하면 안 된다."""
    req = DishTagRequest(dish_name="백미밥", food_category="밥류",
                         nutrition=nutrition("rice"))
    result = combine(req, analysis(is_high_purine=False), "m")
    assert CareType.POTASSIUM not in {t.value for t in result.cares}


def test_판정결과는_응답에_없다():
    """LLM도 이 서비스도 RED/INK/OK를 내지 않는다 (SPEC 8.2)."""
    req = DishTagRequest(dish_name="김치찌개", nutrition=nutrition("kimchi_jjigae"))
    result = combine(req, analysis(), "m")
    dumped = result.model_dump()
    assert "verdict" not in dumped
    assert not any("RED" in str(v) or "INK" in str(v) for v in dumped.values())
