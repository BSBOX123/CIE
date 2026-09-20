"""메뉴 이름을 식약처 DB 이름에 맞추는 규칙 검증.

정확 일치만 보던 때는 태깅된 5,048건 중 260건(5%)에만 영양성분이 붙었다.
나트륨·당류 판정은 이 값에서만 나오므로, 못 붙으면 당뇨·고혈압 사용자에게
"걸리는 것 없음"으로 보인다. 넓히되 엉뚱한 음식을 갖다 붙이지 않는지가 핵심이다.
"""

from __future__ import annotations

from app.nutrition.match import NutritionIndex, aliases, normalize


def rec(name: str, group: str = "음식", klass: str = "품목대표", **amt: str) -> dict:
    base = {
        "FOOD_NM_KR": name,
        "DB_GRP_NM": group,
        "DB_CLASS_NM": klass,
        "FOOD_CD": f"CD-{name}",
    }
    base.update(amt)
    return base


def index(*names: str) -> NutritionIndex:
    return NutritionIndex(rec(n) for n in names)


# ── 이름 다듬기 ─────────────────────────────────────────────────────

def test_공백은_무시한다():
    assert normalize(" 순두부 백반 ") == "순두부백반"


def test_괄호_안은_통용명으로_본다():
    assert "닭갈비" in aliases("닭볶음(닭갈비)")
    assert "닭볶음" in aliases("닭볶음(닭갈비)")


def test_밑줄_뒤는_부재료다():
    assert "김치찌개" in aliases("김치찌개_돼지고기")


# ── 실제로 어긋났던 네 가지 ──────────────────────────────────────────

def test_정확히_같으면_그대로():
    m = index("김치찌개").find("김치찌개")
    assert m.rank == 0 and m.record["FOOD_NM_KR"] == "김치찌개"


def test_괄호_통용명으로_찾는다():
    """닭갈비 → DB 는 '닭볶음(닭갈비)'."""
    m = index("닭볶음(닭갈비)").find("닭갈비")
    assert m.rank == 1 and m.record["FOOD_NM_KR"] == "닭볶음(닭갈비)"


def test_메뉴명_앞의_수식은_떼고_찾는다():
    """숙성생삼겹살 → 삼겹살. 메뉴명 뒤쪽이 음식의 정체다."""
    m = index("삼겹살").find("숙성생삼겹살")
    assert m.rank == 2 and m.matched_name == "삼겹살"


def test_DB_이름이_더_길어도_찾는다():
    """순두부 → DB 는 '초당순두부'."""
    m = index("초당순두부").find("순두부")
    assert m.rank == 3 and m.record["FOOD_NM_KR"] == "초당순두부"


def test_가장_구체적인_꼬리를_고른다():
    """'한우만둣국' 은 '국' 이 아니라 '만둣국' 이다."""
    m = index("만둣국", "국").find("한우만둣국")
    assert m.record["FOOD_NM_KR"] == "만둣국"


# ── 갖다 붙이지 않는 선 ─────────────────────────────────────────────

def test_가운데에_품고_있을_뿐이면_채택하지_않는다():
    """'순두부볶음' 의 '순두부'. 뒤에 오는 볶음이 이 음식의 정체다."""
    assert index("순두부").find("순두부볶음") is None


def test_뒤에_붙은_상차림_표현은_뗀다():
    """'간장게장정식' 은 간장게장이 나오는 상이지 다른 음식이 아니다."""
    m = index("간장게장").find("간장게장정식")
    assert m is not None and m.record["FOOD_NM_KR"] == "간장게장"


def test_상차림_표현을_떼도_남는_이름이_있어야_한다():
    """'정식' 하나만 남으면 무엇인지 알 수 없다."""
    assert index("간장게장").find("정식") is None


def test_한_글자는_핵심_이름으로_보지_않는다():
    """'밥', '국' 은 아무 데나 붙는다."""
    assert index("밥").find("제육덮밥") is None


def test_아예_다른_음식은_None():
    assert index("도넛_바나나크림도넛").find("바나나") is None


def test_가공식품은_이름이_맞아도_쓰지_않는다():
    """실측: '소면' 가공식품 레코드는 건면 제품이라 나트륨이 830mg/100g 이다."""
    idx = NutritionIndex([rec("소면", group="가공식품")])
    assert idx.find("소면") is None


def test_상용제품도_뺀다():
    """간편조리세트·밀키트는 조리 전 제품 값이다."""
    idx = NutritionIndex([rec("순두부찌개", klass="상용제품")])
    assert idx.find("순두부찌개") is None


# ── 여럿일 때 고르는 순서 ───────────────────────────────────────────

def test_외식_레코드를_먼저_고른다():
    idx = NutritionIndex([rec("김치찌개", klass="품목대표"), rec("김치찌개", klass="외식")])
    assert idx.find("김치찌개").record["DB_CLASS_NM"] == "외식"


def test_정확일치가_꼬리일치보다_먼저다():
    idx = index("삼겹살", "생삼겹살")
    assert idx.find("생삼겹살").record["FOOD_NM_KR"] == "생삼겹살"


def test_허용_등급을_낮추면_느슨한_매칭을_막는다():
    """판정 근거를 더 엄격하게 두고 싶을 때 쓴다."""
    idx = index("초당순두부")
    assert idx.find("순두부") is not None
    assert idx.find("순두부", max_rank=2) is None
