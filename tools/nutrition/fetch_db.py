"""식약처 식품영양성분DB 전체를 한 번 받아 로컬 색인으로 만든다.

    PUBLIC_DATA_API_KEY=... python3 tools/nutrition/fetch_db.py

왜 받아 두는가: API 는 ``FOOD_NM_KR`` 부분 일치 검색만 되고 한 번에 100건까지
줘서, 음식 하나를 찾으려면 이름을 바꿔 가며 여러 번 물어야 한다. 태깅된 음식이
5,000건이 넘는 지금은 일일 한도(10,000)에 바로 걸린다. 전체 33만 건을 한 번
받아 두면 이름 맞추기를 로컬에서 몇 번이든 고쳐 볼 수 있다.

식약처 데이터는 "이용허락범위 제한 없음"이라 저장해도 된다. 실시간 호출이
강제되는 것은 관광공사 데이터뿐이다 (공모전 FAQ).

결과: ``tools/nutrition/data/foods.jsonl.gz`` — 식당 음식 판정에 쓸 수 있는
레코드만 남긴다. 가공식품·원재료성·상용제품은 조리 전 제품 값이라 뺀다
(``app/nutrition/client.py`` 의 제외 규칙과 같다).
"""
from __future__ import annotations

import asyncio
import gzip
import json
import os
import sys
from pathlib import Path

import httpx

HERE = Path(__file__).resolve().parent
OUT = HERE / "data" / "foods.jsonl.gz"
URL = "https://apis.data.go.kr/1471000/FoodNtrCpntDbInfo02/getFoodNtrCpntDbInq02"

ROWS = 100  # API 상한. 더 크게 주면 빈 응답이 온다
CONCURRENCY = 8
EXCLUDED_GROUPS = {"가공식품", "원재료성"}
EXCLUDED_CLASSES = {"상용제품"}

#: 저장할 필드. 영양소 값(AMT_NUM*)은 그대로 둬야 field_map 이 읽는다.
KEEP = ("FOOD_CD", "FOOD_NM_KR", "DB_GRP_NM", "DB_CLASS_NM", "FOOD_CAT1_NM", "SERVING_SIZE")


def keep(item: dict) -> bool:
    return (
        (item.get("DB_GRP_NM") or "").strip() not in EXCLUDED_GROUPS
        and (item.get("DB_CLASS_NM") or "").strip() not in EXCLUDED_CLASSES
    )


def slim(item: dict) -> dict:
    out = {k: item.get(k) for k in KEEP}
    out.update({k: v for k, v in item.items() if k.startswith("AMT_NUM") and v not in (None, "")})
    return out


def page_url(key: str, no: int, rows: int = ROWS) -> str:
    """키는 이미 URL 인코딩된 채로 발급된다. params= 로 넘기면 다시 인코딩돼 인증이 깨진다."""
    return f"{URL}?serviceKey={key}&numOfRows={rows}&pageNo={no}&type=json"


async def page(client: httpx.AsyncClient, key: str, no: int) -> list[dict]:
    """한 페이지. 실패하면 몇 번 다시 시도한다 — 3천 번 중 한 번 끊겨도 통째로 버리지 않는다."""
    for attempt in range(1, 4):
        try:
            r = await client.get(page_url(key, no))
            r.raise_for_status()
            body = r.json().get("body") or {}
            return body.get("items") or []
        except Exception as e:  # noqa: BLE001 - 네트워크·JSON 어느 쪽이든 재시도한다
            if attempt == 3:
                print(f"  {no}쪽 실패: {type(e).__name__} {e}", file=sys.stderr)
                return []
            await asyncio.sleep(attempt)
    return []


async def main() -> None:
    key = os.environ.get("PUBLIC_DATA_API_KEY")
    if not key:
        sys.exit("PUBLIC_DATA_API_KEY 가 필요합니다")

    async with httpx.AsyncClient(timeout=60) as client:
        first = await client.get(page_url(key, 1, rows=1))
        total = int((first.json().get("body") or {}).get("totalCount", 0))
        pages = -(-total // ROWS)
        print(f"전체 {total:,}건 / {pages:,}쪽")

        sem = asyncio.Semaphore(CONCURRENCY)
        kept: list[dict] = []
        done = 0

        async def one(no: int) -> None:
            nonlocal done
            async with sem:
                items = await page(client, key, no)
            kept.extend(slim(i) for i in items if keep(i))
            done += 1
            if done % 200 == 0:
                print(f"  {done:,}/{pages:,}쪽 — 남긴 것 {len(kept):,}건")

        await asyncio.gather(*(one(n) for n in range(1, pages + 1)))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    # 이름순으로 정렬해 두면 파일이 바뀌었는지 눈으로 비교하기 쉽다.
    kept.sort(key=lambda x: (x.get("FOOD_NM_KR") or "", x.get("FOOD_CD") or ""))
    with gzip.open(OUT, "wt", encoding="utf-8") as f:
        for item in kept:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
    print(f"저장 {OUT} — {len(kept):,}건 ({OUT.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    asyncio.run(main())
