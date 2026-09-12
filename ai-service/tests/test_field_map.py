"""영양성분DB 필드 매핑 골든 테스트.

이 테스트는 ``app/nutrition/field_map.py`` 의 AMT_NUM 매핑이 옳다는 **유일한
근거**다. 공식 활용가이드 없이 실측으로 확정한 매핑이므로, 여기서 쓰는
음식들은 조성이 널리 알려져 있어 어느 필드가 어느 영양소인지 값만 보고
판별할 수 있는 것들로 골랐다.

fixtures/ 의 JSON은 실제 API 응답을 그대로 저장한 것이다(2026-09-07).
API 스펙이 바뀌면 이 테스트가 먼저 깨져야 한다.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.nutrition.field_map import FIELD_MAP, UNITS, Nutrient, extract, parse_amount

FIXTURES = Path(__file__).parent / "fixtures"


def load(name: str) -> dict[str, str]:
    return json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))


@pytest.fixture
def rice() -> dict[Nutrient, float | None]:
    return extract(load("rice"))


@pytest.fixture
def kimchi() -> dict[Nutrient, float | None]:
    return extract(load("kimchi"))


@pytest.fixture
def pork() -> dict[Nutrient, float | None]:
    return extract(load("pork"))


@pytest.fixture
def squid() -> dict[Nutrient, float | None]:
    return extract(load("squid"))


# ── 나트륨 / 칼륨 판별 ──────────────────────────────────────────────
# 두 성분 모두 본 서비스의 주의성분이며, 뒤바뀌면 만성콩팥병 사용자에게
# 정반대 조언이 나간다. 가장 중요한 테스트.

def test_백미밥_나트륨은_칼륨일_수_없다(rice):
    """백미밥: 나트륨 1~2mg, 칼륨 25~30mg.

    이 필드가 2.0이라는 사실만으로 나트륨임이 확정된다.
    칼륨이라면 최소 20mg대가 나와야 한다.
    """
    assert rice[Nutrient.SODIUM] == pytest.approx(2.0)
    assert rice[Nutrient.SODIUM] < 10, "칼륨이었다면 20mg 이상이어야 한다"


def test_배추김치_나트륨과_칼륨이_동시에_일치한다(kimchi):
    """배추김치 실제값: 칼륨 약 330mg, 나트륨 약 550mg.

    한쪽만 맞는 우연이 아니라 양쪽이 동시에 맞아야 매핑이 확정된다.
    """
    assert kimchi[Nutrient.POTASSIUM] == pytest.approx(334.0)
    assert kimchi[Nutrient.SODIUM] == pytest.approx(551.0)
    assert kimchi[Nutrient.SODIUM] > kimchi[Nutrient.POTASSIUM]


def test_오징어채볶음은_고나트륨_음식이다(squid):
    assert squid[Nutrient.SODIUM] == pytest.approx(975.0)


# ── 콜레스테롤 / 포화지방 판별 ──────────────────────────────────────
# 두 필드가 인접해 있어 뒤바뀌기 쉽다. 양상이 정반대인 두 음식으로 교차 확인.

def test_오징어는_콜레스테롤_높고_포화지방_낮다(squid):
    assert squid[Nutrient.CHOLESTEROL] == pytest.approx(183.61)
    assert squid[Nutrient.SATURATED_FAT] == pytest.approx(1.17)
    assert squid[Nutrient.CHOLESTEROL] > squid[Nutrient.SATURATED_FAT] * 10


def test_삼겹살은_포화지방_높고_콜레스테롤_상대적_낮다(pork):
    """오징어와 정반대 양상. 두 필드가 서로 뒤바뀌지 않았음을 교차 확인."""
    assert pork[Nutrient.SATURATED_FAT] == pytest.approx(12.42)
    assert pork[Nutrient.CHOLESTEROL] == pytest.approx(39.72)
    assert pork[Nutrient.SATURATED_FAT] > squid_sat_fat()


def squid_sat_fat() -> float:
    return float(load("squid")["AMT_NUM24"])


def test_포화지방은_총지방을_넘을_수_없다(pork, squid, kimchi):
    for label, food in [("삼겹살", pork), ("오징어채", squid), ("김치", kimchi)]:
        fat, sat = food[Nutrient.FAT], food[Nutrient.SATURATED_FAT]
        if fat is None or sat is None:
            continue
        assert sat <= fat, f"{label}: 포화지방({sat}) > 총지방({fat})"


# ── 다량영양소 ──────────────────────────────────────────────────────

def test_삼겹살_다량영양소(pork):
    assert pork[Nutrient.ENERGY] == pytest.approx(467.0)
    assert pork[Nutrient.PROTEIN] == pytest.approx(22.56)
    assert pork[Nutrient.FAT] == pytest.approx(41.69)


def test_백미밥은_탄수화물_위주다(rice):
    assert rice[Nutrient.ENERGY] == pytest.approx(148.0)
    assert rice[Nutrient.CARBOHYDRATE] == pytest.approx(30.10)
    assert rice[Nutrient.FAT] == pytest.approx(0.20)
    assert rice[Nutrient.SUGAR] == pytest.approx(0.10)


# ── 결측 처리 ───────────────────────────────────────────────────────

def test_결측은_0이_아니라_None이다(rice):
    """백미밥은 가공식품 레코드라 칼륨이 측정되지 않았다.

    측정 안 됨(None)과 실제 0을 구분하지 못하면, 미측정 성분을 '0 = 안전'
    으로 잘못 판정하게 된다.
    """
    assert rice[Nutrient.POTASSIUM] is None
    assert rice[Nutrient.SATURATED_FAT] == 0.0  # 이쪽은 실제 측정된 0


@pytest.mark.parametrize(
    "raw,expected",
    [("", None), (None, None), ("  ", None), ("0.00", 0.0),
     ("551.000", 551.0), ("N/A", None)],
)
def test_parse_amount(raw, expected):
    assert parse_amount(raw) == expected


# ── 매핑 자체의 무결성 ──────────────────────────────────────────────

def test_모든_영양소에_필드와_단위가_있다():
    for nutrient in Nutrient:
        assert nutrient in FIELD_MAP, f"{nutrient} 필드 매핑 누락"
        assert nutrient in UNITS, f"{nutrient} 단위 누락"


def test_필드가_중복_배정되지_않았다():
    fields = list(FIELD_MAP.values())
    assert len(fields) == len(set(fields)), "두 영양소가 같은 AMT_NUM을 가리킨다"
