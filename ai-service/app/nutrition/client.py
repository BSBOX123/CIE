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
_SOURCE_RANK: dict[str, int] = {
    "외식(분석 함량)": 0,
    "가정식(분석 함량)": 1,
    "외식": 2,
    "가정식": 3,
}

#: 조리 전 제품이라 조리된 음식 판정에 쓸 수 없는 조리 형태.
_EXCLUDED_SOURCES: frozenset[str] = frozenset({"가공식품"})

#: 접두 일치에서 허용하는 구분자. 이 뒤는 부재료 수식으로 본다.
#: ``김치찌개_돼지고기`` 는 허용하지만 ``명품한우등심 언양식불고기`` 는
#: 공백으로 이어진 다른 메뉴명이므로 허용하지 않는다.
_MODIFIER_PREFIXES: tuple[str, ...] = ("_", "(")


def _rank(item: dict[str, str], query: str) -> tuple[int, int] | None:
    """(이름 일치 등급, 조리형태 등급). 채택 불가면 ``None``."""
    source = (item.get("FOOD_OR_NM") or "").strip()
    if source in _EXCLUDED_SOURCES:
        return None

    name = (item.get("FOOD_NM_KR") or "").strip()
    if name == query:
        name_rank = 0
    elif name.startswith(query) and name[len(query):].startswith(_MODIFIER_PREFIXES):
        name_rank = 1
    else:
        return None
    return name_rank, _SOURCE_RANK.get(source, 9)


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
        source_kind=item.get("FOOD_OR_NM"),
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
