"""식당 메뉴 이름을 식약처 DB 이름에 맞춘다.

식약처 DB 는 표준 이름으로 등록돼 있고, 식당 메뉴명은 그렇지 않다. 실측한
어긋남은 네 가지였다.

```
닭갈비        DB 는 "닭볶음(닭갈비)"      괄호 안이 통용명
순두부        DB 는 "초당순두부"          DB 이름이 더 길다
숙성생삼겹살   DB 는 "삼겹살"              메뉴명에 수식이 붙는다
김치찌개       DB 는 "김치찌개_돼지고기"    부재료가 덧붙는다
```

정확 일치와 접두 일치만 채택하던 때는 태깅된 5,048건 중 260건(5%)만 붙었다.
나트륨·당류 판정은 이 값에서만 나오므로, 못 붙은 음식은 당뇨·고혈압 사용자에게
"걸리는 것 없음"으로 보인다. 그래서 규칙을 넓히되, **아무 음식이나 갖다 붙이지
않도록** 어디까지 허용했는지 등급으로 남긴다({@link Match.rank}).

넓히지 않은 것: 메뉴명이 DB 이름을 가운데에 품고 있을 뿐인 경우
("순두부백반" 의 "순두부")는 채택하지 않는다. 주재료가 앞에 오고 음식 종류가
뒤에 오는 한국어 특성상, 뒤쪽이 그 음식의 정체다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Iterable

#: 조리 형태 우선순위. 낮을수록 식당 음식에 가깝다 (client.py 와 같은 기준).
_CLASS_RANK: dict[str, int] = {"외식": 0, "품목대표": 1}

_EXCLUDED_GROUPS = frozenset({"가공식품", "원재료성"})
_EXCLUDED_CLASSES = frozenset({"상용제품"})

#: 이름에서 떼어 내는 군더더기. 괄호 안은 통용명일 수 있어 따로 다룬다.
_SPACE = re.compile(r"\s+")

#: 핵심 이름으로 인정하는 최소 길이. "국", "밥" 한 글자는 아무 데나 붙는다.
MIN_CORE = 2

#: 메뉴 뒤에 붙는 상차림 표현. 음식 이름이 아니라 내는 방식이다.
#: "간장게장정식" 은 간장게장이 나오는 상이지 다른 음식이 아니다.
_SERVING_SUFFIXES: tuple[str, ...] = (
    "정식", "세트", "한상", "특선", "코스", "도시락", "백반", "상차림", "요리",
)


def normalize(name: str | None) -> str:
    """비교용 이름. 공백을 없애고 소문자로."""
    if not name:
        return ""
    return _SPACE.sub("", name.strip()).lower()


def aliases(db_name: str) -> list[str]:
    """DB 이름이 품고 있는 통용명.

    ``닭볶음(닭갈비)`` → ``닭볶음``, ``닭갈비``.
    ``김치찌개_돼지고기`` → ``김치찌개`` (밑줄 뒤는 부재료).
    """
    name = normalize(db_name)
    if not name:
        return []
    out = [name]
    head = name.split("_", 1)[0]
    if head and head != name:
        out.append(head)
    for inner in re.findall(r"\(([^)]*)\)", name):
        inner = inner.strip()
        if len(inner) >= MIN_CORE:
            out.append(inner)
    outside = re.sub(r"\([^)]*\)", "", name).strip()
    if len(outside) >= MIN_CORE:
        out.append(outside)
    # 밑줄 뒤 부재료를 뗀 괄호 바깥 이름까지 (예: "닭볶음(닭갈비)_생것")
    head_outside = re.sub(r"\([^)]*\)", "", head).strip()
    if len(head_outside) >= MIN_CORE:
        out.append(head_outside)
    seen: list[str] = []
    for a in out:
        if a and a not in seen:
            seen.append(a)
    return seen


@dataclass(frozen=True)
class Match:
    """고른 레코드와 어떻게 골랐는지.

    rank 0 이름이 그대로 같다
    rank 1 DB 이름의 통용명·부재료를 뗀 이름과 같다 (닭갈비 = 닭볶음(닭갈비))
    rank 2 메뉴명의 뒷부분이 DB 이름과 같다 (숙성생삼겹살 → 삼겹살)
    rank 3 DB 이름이 메뉴명으로 끝난다 (순두부 → 초당순두부)
    """

    record: dict
    rank: int
    matched_name: str


class NutritionIndex:
    """이름으로 레코드를 찾는 색인. 33만 건을 메모리에 올려도 수십 MB 다."""

    def __init__(self, records: Iterable[dict]) -> None:
        self._by_alias: dict[str, list[dict]] = {}
        self._by_tail: dict[str, list[dict]] = {}
        self._exact: dict[str, list[dict]] = {}
        for record in records:
            if not self._usable(record):
                continue
            name = normalize(record.get("FOOD_NM_KR"))
            if not name:
                continue
            self._exact.setdefault(name, []).append(record)
            for alias in aliases(name):
                self._by_alias.setdefault(alias, []).append(record)
            # DB 이름의 꼬리들. "초당순두부" 는 "순두부" 로도 찾힌다.
            for i in range(1, len(name) - MIN_CORE + 1):
                self._by_tail.setdefault(name[i:], []).append(record)

    @staticmethod
    def _usable(record: dict) -> bool:
        """조리 전 제품 값은 쓰지 않는다. 삶은 소면과 건면은 나트륨이 수백 배 다르다."""
        return (
            (record.get("DB_GRP_NM") or "").strip() not in _EXCLUDED_GROUPS
            and (record.get("DB_CLASS_NM") or "").strip() not in _EXCLUDED_CLASSES
        )

    def find(self, dish_name: str, *, max_rank: int = 3) -> Match | None:
        """메뉴 이름에 맞는 레코드. 없으면 ``None``."""
        dish = normalize(dish_name)
        if len(dish) < MIN_CORE:
            return None

        if (hit := self._pick(self._exact.get(dish))) is not None:
            return Match(hit, 0, dish)
        if max_rank >= 1 and (hit := self._pick(self._by_alias.get(dish))) is not None:
            return Match(hit, 1, dish)
        if max_rank >= 2:
            # 메뉴명 뒤쪽이 음식의 정체다. 긴 꼬리부터 봐서 가장 구체적인 것을 고른다.
            #
            # 여기서는 통용명을 보지 않고 **DB 이름과 그대로 같을 때만** 받는다.
            # 둘을 겹치면 "화이트갈릭버거"의 꼬리 "버거"가 "버거_치킨킹"의 앞머리와
            # 맞아 엉뚱한 제품의 영양성분이 붙었다. 분류만 같은 다른 음식이다.
            for i in range(1, len(dish) - MIN_CORE + 1):
                found = self._pick(self._exact.get(dish[i:]))
                if found is not None:
                    return Match(found, 2, dish[i:])
        if max_rank >= 2:
            # 뒤에 붙은 상차림 표현을 떼고 다시 본다 (간장게장정식 → 간장게장).
            for suffix in _SERVING_SUFFIXES:
                if dish.endswith(suffix) and len(dish) - len(suffix) >= MIN_CORE:
                    shorter = self.find(dish[: -len(suffix)], max_rank=2)
                    if shorter is not None:
                        return Match(shorter.record, 2, shorter.matched_name)
        if max_rank >= 3 and (hit := self._pick(self._by_tail.get(dish))) is not None:
            return Match(hit, 3, normalize(hit.get("FOOD_NM_KR")))
        return None

    @staticmethod
    def _pick(records: list[dict] | None) -> dict | None:
        """같은 이름이 여럿이면 식당 음식에 가까운 것, 그다음 이름이 짧은 것."""
        if not records:
            return None
        return min(
            records,
            key=lambda r: (
                _CLASS_RANK.get((r.get("DB_CLASS_NM") or "").strip(), 9),
                len(normalize(r.get("FOOD_NM_KR"))),
                normalize(r.get("FOOD_NM_KR")),
            ),
        )
