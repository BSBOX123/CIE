"""주의성분 임계값 판정 테스트 (SPEC 7.3).

실제 음식 fixture로 검증한다. 임계값이 현실의 음식에 대해 상식적인 결과를
내는지가 핵심이며, 특히 **미측정을 안전으로 읽지 않는지**를 확인한다.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.nutrition.field_map import Nutrient, extract
from app.tagging.rules import (
    LLM_ONLY,
    THRESHOLDS,
    Determination,
    evaluate,
    flagged_cares,
    unresolved_cares,
)
from app.tagging.vocabulary import CARE_NOTES, DISEASE_MAP, CareType, Disease

FIXTURES = Path(__file__).parent / "fixtures"


def nutrition(name: str) -> dict[Nutrient, float | None]:
    return extract(json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8")))


# ── 실제 음식 판정 (1회 섭취량 기준) ────────────────────────────────
# SPEC 7.3 개정. 100g 기준으로는 한국 음식이 거의 안 걸리는 문제를 바로잡았다.

def test_김치찌개는_고나트륨으로_잡힌다():
    """491mg/100g × 300g = 1,473mg — 하루 목표 2,000mg의 74%를 한 그릇에서.

    100g 기준(600mg)으로는 통과해 버렸다. 고혈압 사용자가 김치찌개에
    '먹어도 돼요'를 받는 것은 서비스 실패다.
    """
    assert CareType.SODIUM in flagged_cares(nutrition("kimchi_jjigae"), "찌개 및 전골류")


def test_된장찌개도_고나트륨으로_잡힌다():
    """318mg/100g × 300g = 954mg."""
    assert CareType.SODIUM in flagged_cares(nutrition("doenjang"), "찌개 및 전골류")


def test_라면은_면류_섭취량으로_잡힌다():
    """283mg/100g × 400g = 1,132mg."""
    assert CareType.SODIUM in flagged_cares(nutrition("ramen"), "면 및 만두류")


def test_배추김치는_적게_먹으므로_나트륨이_잡히지_않는다():
    """551mg/100g 로 농도는 높지만 1회 40g = 220mg.

    농도만 보면 김치가 김치찌개보다 짜지만, 실제 섭취량을 반영하면 반대다.
    이 구분이 되어야 사용자에게 쓸모 있는 조언이 된다.
    """
    cares = flagged_cares(nutrition("kimchi"), "김치류")
    assert CareType.SODIUM not in cares
    assert CareType.POTASSIUM not in cares  # 334 × 0.4 = 134mg


def test_삼겹살은_포화지방과_나트륨이_잡힌다():
    cares = flagged_cares(nutrition("pork"), "구이류")
    assert CareType.SATURATED_FAT in cares   # 12.42 × 1.2 = 14.9g
    assert CareType.SODIUM in cares          # 576 × 1.2 = 691mg


def test_백미밥은_걸리는_주의성분이_없다():
    """나트륨 2mg × 2.5 = 5mg. 정제 탄수화물은 LLM 몫이라 여기 없다."""
    assert flagged_cares(nutrition("rice"), "밥류") == []


def test_오징어채볶음은_고나트륨이다():
    """975mg/100g × 100g(볶음류) = 975mg."""
    assert CareType.SODIUM in flagged_cares(nutrition("squid"), "볶음류")


# ── 안전 요건: 미측정을 안전으로 읽지 않는다 ────────────────────────

def test_미측정_칼륨은_CLEAR가_아니라_UNKNOWN이다():
    """백미밥(가공식품 레코드)은 칼륨이 비어 있다.

    이걸 0으로 읽어 '칼륨 걱정 없음'으로 판정하면 만성콩팥병 사용자에게
    잘못된 안내가 나간다. 반드시 UNKNOWN으로 남아 LLM에 위임되어야 한다.
    """
    results = {d.care: d for d in evaluate(nutrition("rice"), "밥류")}
    assert results[CareType.POTASSIUM].result is Determination.UNKNOWN
    assert results[CareType.POTASSIUM].result is not Determination.CLEAR


def test_미측정_성분은_LLM_위임_목록에_들어간다():
    unresolved = unresolved_cares(nutrition("rice"), "밥류")
    assert CareType.POTASSIUM in unresolved


def test_측정된_0은_UNKNOWN이_아니라_CLEAR다():
    """백미밥 포화지방은 실제로 측정된 0.00이다. 미측정과 구분되어야 한다."""
    results = {d.care: d for d in evaluate(nutrition("rice"), "밥류")}
    assert results[CareType.SATURATED_FAT].result is Determination.CLEAR


# ── 임계값 경계 ─────────────────────────────────────────────────────

@pytest.mark.parametrize(
    "per_100g,expected",
    [(599.9, Determination.CLEAR), (600.0, Determination.FLAGGED),
     (600.1, Determination.FLAGGED)],
)
def test_나트륨_임계값_경계는_이상으로_처리한다(per_100g, expected):
    """식품군을 100g 섭취로 두면 100g 수치가 그대로 1회 섭취량이 된다."""
    results = {
        d.care: d
        for d in evaluate({Nutrient.SODIUM: per_100g}, "볶음류")  # 볶음류 = 100g
    }
    assert results[CareType.SODIUM].result is expected


def test_같은_농도라도_식품군에_따라_판정이_달라진다():
    """구조적 개선의 핵심: 농도가 같아도 먹는 양이 다르면 결과가 달라야 한다."""
    facts = {Nutrient.SODIUM: 400.0}
    assert CareType.SODIUM in flagged_cares(facts, "찌개 및 전골류")  # ×3.0 = 1200
    assert CareType.SODIUM not in flagged_cares(facts, "김치류")      # ×0.4 = 160


# ── 어휘 무결성 ─────────────────────────────────────────────────────

def test_주의성분_7종이_모두_처리된다():
    """수치 판정 5종 + LLM 전담 2종 = 7종. 빠지는 성분이 없어야 한다."""
    covered = set(THRESHOLDS) | LLM_ONLY
    assert covered == set(CareType), f"누락: {set(CareType) - covered}"


def test_수치판정과_LLM전담은_겹치지_않는다():
    assert not (set(THRESHOLDS) & LLM_ONLY)


def test_모든_주의성분에_안내문구가_있다():
    for care in CareType:
        assert CARE_NOTES.get(care), f"{care} 안내 문구 누락"


def test_질환매핑이_주의성분_어휘_안에_있다():
    for disease, cares in DISEASE_MAP.items():
        assert cares, f"{disease} 매핑 비어 있음"
        for care in cares:
            assert care in CareType


def test_질환_5종이_모두_매핑된다():
    assert set(DISEASE_MAP) == set(Disease)


def test_만성콩팥병은_나트륨_칼륨_단백질량을_모두_포함한다():
    """SPEC 2.3. 칼륨이 빠지면 이 서비스의 존재 이유가 훼손된다."""
    cares = DISEASE_MAP[Disease.CKD]
    assert CareType.SODIUM in cares
    assert CareType.POTASSIUM in cares
    assert CareType.PROTEIN_LOAD in cares
