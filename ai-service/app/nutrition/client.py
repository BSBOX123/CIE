"""식약처 식품영양성분DB 클라이언트.

이 API는 ``FOOD_NM_KR`` 을 **부분 일치**로 검색한다. "바나나"를 넣으면
`도넛_바나나크림도넛`, `마카롱_바나나누텔라` 같은 가공식품이 상위를 점령한다
(SPEC 6.4-a). 따라서 응답을 그대로 신뢰하지 않고 아래 순서로 후보를 고른다.

1. ``FOOD_NM_KR`` 이 검색어와 정확히 일치
2. 접두 일치 — 단, 뒤에 붙은 것이 **부재료 수식**일 때만
   (``김치찌개`` ← ``김치찌개_돼지고기``는 허용,
   ``명품한우등심`` ← ``명품한우등심 언양식불고기``는 **다른 음식**이므로 거부)
3. 그 외는 버린다 — 엉뚱한 음식의 영양성분으로 판정하느니 LLM에 위임한다.

**가공식품 레코드는 쓰지 않는다.** 이 서비스가 판정하는 것은 식당에서 나오는
조리된 음식인데, 가공식품 레코드는 조리 전 제품 값이다. 실측 예: ``소면`` 의
가공식품 레코드는 나트륨 830mg/100g(건면 제품)이지만, 삶은 소면은 소금이
빠져나가 2mg 수준이다. 이 값으로 판정하면 멀쩡한 음식이 고나트륨으로 찍힌다.
"""

from __future__ import annotations

import httpx

from app.config import settings
from app.nutrition.field_map import extract
from app.nutrition.models import NutritionFacts

#: 조리 형태 우선순위 (낮을수록 우선). SPEC 6.4-a.
#: 조리 형태 우선순위. 이 서비스가 판정하는 것은 식당에서 나오는 조리된 음식이다.
#:
#: 원래 ``FOOD_OR_NM`` 을 봤는데, 이 필드는 응답에 있기는 하나 값이 항상 ``None``
#: 이다. ``None or ""`` 이 빈 문자열이 되어 아래 제외 조건이 한 번도 참이 되지
#: 않았고, 예외도 경고도 없이 조용히 통과했다. 실측해 보니 채택된 레코드의
#: **절반이 가공식품**이었다. 분류는 ``DB_GRP_NM`` / ``DB_CLASS_NM`` 에 있다.
_CLASS_RANK: dict[str, int] = {
    "외식": 0,      # 식당에서 나오는 그대로. 가장 가깝다
    "품목대표": 1,  # 그 음식의 표준 대표값
}

#: 쓰지 않는 대분류.
#:
#: ``가공식품`` 은 조리 전 제품 값이다. 실측: ``소면`` 의 가공식품 레코드는
#: 나트륨 830mg/100g(건면 제품)이지만 삶은 소면은 그렇지 않다.
#: ``원재료성`` 은 ``돼지고기, 앞다리(항정살), 생것`` 처럼 조리 전 재료다.
_EXCLUDED_GROUPS: frozenset[str] = frozenset({"가공식품", "원재료성"})

#: 접두 일치에서 허용하는 구분자. 이 뒤는 부재료 수식으로 본다.
#: ``김치찌개_돼지고기`` 는 허용하지만 ``명품한우등심 언양식불고기`` 는
#: 공백으로 이어진 다른 메뉴명이므로 허용하지 않는다.
_MODIFIER_PREFIXES: tuple[str, ...] = ("_", "(")

#: 쓰지 않는 세부분류. ``음식`` 으로 분류돼 있어도 간편조리세트·밀키트는
#: 조리 전 제품 값이라 식당 음식과 다르다.
#: 예: ``순두부찌개_간편조리세트_강릉식 짬뽕 순두부``
_EXCLUDED_CLASSES: frozenset[str] = frozenset({"상용제품"})

def _rank(item: dict[str, str], query: str) -> tuple[int, int] | None:
    """(이름 일치 등급, 조리형태 등급). 채택 불가면 ``None``."""
    group = (item.get("DB_GRP_NM") or "").strip()
    klass = (item.get("DB_CLASS_NM") or "").strip()
    if group in _EXCLUDED_GROUPS or klass in _EXCLUDED_CLASSES:
        return None

    name = (item.get("FOOD_NM_KR") or "").strip()
    if name == query:
        name_rank = 0
    elif name.startswith(query) and name[len(query):].startswith(_MODIFIER_PREFIXES):
        name_rank = 1
    else:
        return None
    return name_rank, _CLASS_RANK.get(klass, 9)


def pick_best(items: list[dict[str, str]], query: str) -> dict[str, str] | None:
    """부분 일치 응답에서 실제로 쓸 만한 레코드 하나를 고른다."""
    scored = [(r, it) for it in items if (r := _rank(it, query)) is not None]
    if not scored:
        return None
    return min(scored, key=lambda pair: pair[0])[1]


def to_facts(item: dict[str, str]) -> NutritionFacts:
    return NutritionFacts(
        food_code=item.get("FOOD_CD"),
        food_name=item.get("FOOD_NM_KR"),
        # FOOD_OR_NM 은 항상 비어 있다. 실제 분류는 DB_GRP_NM/DB_CLASS_NM.
        source_kind=item.get("DB_CLASS_NM"),
        food_category=item.get("FOOD_CAT1_NM"),
        serving_size=item.get("SERVING_SIZE"),
        values=extract(item),
    )


class NutritionClient:
    """식약처 영양성분DB 조회."""

    def __init__(self, api_key: str | None = None, base_url: str | None = None) -> None:
        # 키는 이미 URL 인코딩된 상태로 배포된다. 재인코딩하면 인증이 깨진다.
        self._api_key = api_key or settings.public_data_api_key
        self._base_url = base_url or settings.nutrition_base_url

    async def lookup(self, dish_name: str, *, rows: int = 100) -> NutritionFacts | None:
        """음식명으로 영양성분을 조회한다. 신뢰할 만한 매칭이 없으면 ``None``."""
        url = (
            f"{self._base_url}?serviceKey={self._api_key}"
            f"&numOfRows={rows}&pageNo=1&type=json&FOOD_NM_KR={dish_name}"
        )
        async with httpx.AsyncClient(timeout=settings.request_timeout_seconds) as client:
            response = await client.get(url)
            response.raise_for_status()
            payload = response.json()

        items = payload.get("body", {}).get("items") or []
        best = pick_best(items, dish_name)
        return to_facts(best) if best else None
