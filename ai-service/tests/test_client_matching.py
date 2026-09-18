"""부분 일치 응답에서 올바른 레코드를 고르는지 검증 (SPEC 6.4-a).

이 API의 가장 큰 함정은 "바나나"를 검색하면 도넛·마카롱이 나온다는 것이다.
엉뚱한 음식의 영양성분으로 판정하면 조용히 틀린 답이 나가므로, 매칭 실패는
반드시 None(=LLM 위임)으로 떨어져야 한다.
"""

from __future__ import annotations

from app.nutrition.client import pick_best, to_facts
from app.nutrition.field_map import Nutrient


def item(
    name: str, group: str = "음식", klass: str = "품목대표", **amt: str
) -> dict[str, str]:
    """실제 응답의 분류 필드를 그대로 쓴다.

    ``FOOD_OR_NM`` 은 응답에 있기는 하나 값이 항상 ``None`` 이라 쓸 수 없다.
    이 테스트가 그 필드로 쓰여 있던 탓에, 가공식품 배제가 운영에서 한 번도
    동작하지 않는 것을 잡지 못했다.
    """
    base = {
        "FOOD_NM_KR": name,
        "DB_GRP_NM": group,
        "DB_CLASS_NM": klass,
        "FOOD_OR_NM": None,  # 실제 응답과 같게 비워 둔다
        "FOOD_CD": f"CD-{name}",
    }
    base.update(amt)
    return base


def test_정확일치를_접두일치보다_우선한다():
    items = [item("김치찌개_돼지고기"), item("김치찌개")]
    assert pick_best(items, "김치찌개")["FOOD_NM_KR"] == "김치찌개"


def test_외식_레코드를_품목대표보다_우선한다():
    """본 서비스는 외식 상황이 대상이다."""
    items = [item("김치찌개", klass="품목대표"), item("김치찌개", klass="외식")]
    assert pick_best(items, "김치찌개")["DB_CLASS_NM"] == "외식"


def test_무관한_가공식품은_버린다():
    """'바나나' 검색에 도넛·마카롱만 나오면 채택하지 않는다."""
    items = [item("도넛_바나나크림도넛"), item("마카롱_바나나누텔라")]
    assert pick_best(items, "바나나") is None


def test_후보가_없으면_None():
    assert pick_best([], "물회") is None


# ── 가공식품 배제 ───────────────────────────────────────────────────
# 가공식품 레코드는 조리 전 제품 값이라 식당 음식 판정에 쓸 수 없다.

def test_가공식품_레코드는_이름이_정확히_맞아도_쓰지_않는다():
    """실측: '소면' 가공식품 레코드는 나트륨 830mg/100g 인 건면 제품이다.

    삶은 소면은 소금이 빠져나가 2mg 수준인데, 이 값을 쓰면 멀쩡한 음식이
    고나트륨으로 찍힌다.
    """
    assert pick_best([item("소면", group="가공식품", klass="상용제품", AMT_NUM13="830")], "소면") is None


def test_가공식품밖에_없으면_LLM에_위임한다():
    items = [item("흑염소탕", group="가공식품")]
    assert pick_best(items, "흑염소탕") is None


def test_음식_레코드가_있으면_그것을_쓴다():
    items = [item("김치찌개", group="가공식품"), item("김치찌개")]
    assert pick_best(items, "김치찌개")["DB_GRP_NM"] == "음식"


def test_간편조리세트는_음식으로_분류돼도_쓰지_않는다():
    """``음식 / 상용제품`` 은 밀키트다. 조리 전 제품 값이라 식당 음식과 다르다.

    예: ``순두부찌개_간편조리세트_강릉식 짬뽕 순두부``
    """
    items = [item("순두부찌개", klass="상용제품")]
    assert pick_best(items, "순두부찌개") is None


def test_원재료성_레코드는_쓰지_않는다():
    """``돼지고기, 앞다리(항정살), 생것`` 같은 조리 전 재료값이다."""
    items = [item("항정살", group="원재료성")]
    assert pick_best(items, "항정살") is None


# ── 접두 일치 제한 ──────────────────────────────────────────────────

def test_부재료_수식_접두일치는_허용한다():
    """'김치찌개_돼지고기'는 김치찌개의 한 종류다."""
    items = [item("순두부찌개_해물")]
    assert pick_best(items, "순두부찌개")["FOOD_NM_KR"] == "순두부찌개_해물"


def test_괄호로_이어진_수식도_허용한다():
    items = [item("설렁탕(고기추가)")]
    assert pick_best(items, "설렁탕")["FOOD_NM_KR"] == "설렁탕(고기추가)"


def test_공백으로_이어진_다른_메뉴명은_거부한다():
    """실측: '명품한우등심' 검색이 '명품한우등심 언양식불고기'를 잡았다.

    등심구이와 불고기는 다른 음식이고 영양성분도 다르다.
    """
    items = [item("명품한우등심 언양식불고기")]
    assert pick_best(items, "명품한우등심") is None


def test_정확일치가_있으면_공백_접두는_문제되지_않는다():
    items = [item("명품한우등심 언양식불고기"), item("명품한우등심")]
    assert pick_best(items, "명품한우등심")["FOOD_NM_KR"] == "명품한우등심"


# ── 값 변환 ─────────────────────────────────────────────────────────

def test_to_facts가_영양소를_매핑한다():
    facts = to_facts(item("배추김치",
                          AMT_NUM12="334.000", AMT_NUM13="551.000"))
    assert facts.get(Nutrient.POTASSIUM) == 334.0
    assert facts.get(Nutrient.SODIUM) == 551.0
    assert facts.food_name == "배추김치"


def test_결측_영양소는_None으로_남는다():
    facts = to_facts(item("백미밥", AMT_NUM13="2.00"))
    assert facts.get(Nutrient.SODIUM) == 2.0
    assert facts.get(Nutrient.POTASSIUM) is None
