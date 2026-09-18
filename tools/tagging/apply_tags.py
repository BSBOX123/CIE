"""사람이 만든 LLM 분석을 기존 태깅 파이프라인에 먹여 DB에 저장한다.

Gemini 호출만 대체하고, 영양성분 조회·임계값 비교·검수 판정은 기존 코드
(app.tagging.service.combine)가 그대로 한다. 무료 티어 15 RPM 으로는
4,992건에 며칠이 걸려, 자주 나오는 음식만 먼저 처리하려고 만들었다.

사용법:

    # 미리보기 (저장하지 않음)
    PUBLIC_DATA_API_KEY=... DB_PASSWORD=... \
      ai-service/.venv/bin/python tools/tagging/apply_tags.py batch01

    # 실제 저장
    ... apply_tags.py batch01 --apply

analysis/<배치>.jsonl 한 줄이 음식 하나다:

    n   음식명 (dish.normalized_name 과 정확히 같아야 한다)
    i   주요 재료
    a   알레르겐 [[값, MAIN|TRACE, 근거], ...]  ← 식약처 표시대상 19종만
    c   정제 탄수화물인지 (bool)
    p   퓨린이 높은지 (bool)
    pr  퓨린 판단 근거
    f   신뢰도 0~1

DB 접속은 SSM 터널(localhost:3308)을 전제로 한다. docs/DEPLOY.md 참조.
"""
import asyncio
import json
import os
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "ai-service"))

from app.nutrition.client import NutritionClient  # noqa: E402
from app.tagging.models import (  # noqa: E402
    AllergenFinding,
    DishTagRequest,
    LlmDishAnalysis,
)
from app.tagging.service import combine  # noqa: E402

MODEL_ID = "claude-opus-5"
#: 식약처 표시대상 19종. 여기서 벗어난 값은 사용자 프로필과 교집합이 나지 않아
#: 판정이 조용히 실패한다.
ALLERGENS = {
    "게", "고등어", "난류", "닭고기", "대두", "돼지고기", "땅콩", "메밀", "밀",
    "복숭아", "새우", "쇠고기", "아황산류", "오징어", "우유", "잣", "조개류",
    "토마토", "호두",
}

batch = next((a for a in sys.argv[1:] if not a.startswith("--")), None)
if not batch:
    sys.exit("배치 이름이 필요합니다. 예: apply_tags.py batch01 --apply")
apply = "--apply" in sys.argv

rows = [json.loads(line) for line in open(f"{HERE}/analysis/{batch}.jsonl", encoding="utf-8")]

# ── 어휘 검증. 틀린 값이 하나라도 있으면 저장 전에 멈춘다 ──
bad = [(r["n"], a[0]) for r in rows for a in r["a"] if a[0] not in ALLERGENS]
bad += [(r["n"], a[1]) for r in rows for a in r["a"] if a[1] not in ("MAIN", "TRACE")]
if bad:
    sys.exit(f"어휘에 없는 값: {bad[:10]}")

import pymysql  # noqa: E402

conn = pymysql.connect(
    host="127.0.0.1", port=3308, user="meogeodo",
    password=os.environ["DB_PASSWORD"], database="meogeodo", charset="utf8mb4",
)

# ── 음식 id 는 DB 에서 이름으로 찾는다. 목록 파일을 따로 두면 금세 어긋난다 ──
names = [r["n"] for r in rows]
with conn.cursor() as cur:
    cur.execute(
        "SELECT id, normalized_name FROM dish WHERE normalized_name IN (%s)"
        % ",".join(["%s"] * len(names)),
        names,
    )
    dish_ids = {name: did for did, name in cur.fetchall()}
missing = [n for n in names if n not in dish_ids]
if missing:
    print(f"  DB 에 없는 음식 {len(missing)}건은 건너뜁니다: {missing[:5]}")
rows = [r for r in rows if r["n"] in dish_ids]

# ── 1. 영양성분 조회 (식약처 공공 API. Gemini 와 무관하다) ──
async def fetch():
    client = NutritionClient(api_key=os.environ["PUBLIC_DATA_API_KEY"])
    sem = asyncio.Semaphore(8)  # 공공 API 를 과하게 때리지 않는다
    out = {}

    async def one(name):
        async with sem:
            try:
                out[name] = await client.lookup(name)
            except Exception as e:  # 조회 실패는 정상 경로다. LLM 추정만으로 간다
                print(f"  영양성분 조회 실패 {name}: {e}")
                out[name] = None

    await asyncio.gather(*(one(r["n"]) for r in rows))
    return out


nutrition = asyncio.run(fetch())
print(f"영양성분 매칭: {sum(1 for v in nutrition.values() if v)}/{len(rows)}건")

# ── 2. 기존 파이프라인으로 합친다 ──
results = []
for r in rows:
    facts = nutrition.get(r["n"])
    request = DishTagRequest(
        dish_name=r["n"],
        # 식품군이 없으면 1회 섭취량이 기본 150g 으로 고정돼 국·탕·면의
        # 나트륨이 과소평가된다.
        food_category=facts.food_category if facts else None,
        nutrition=facts.values if facts else None,
    )
    analysis = LlmDishAnalysis(
        ingredients=r["i"],
        allergens=[AllergenFinding(allergen=a[0], amount=a[1], reason=a[2]) for a in r["a"]],
        is_refined_carb_staple=r["c"],
        is_high_purine=r["p"],
        purine_reasoning=r["pr"],
        confidence=r["f"],
    )
    results.append((r["n"], combine(request, analysis, MODEL_ID)))

cares = sum(len(x.cares) for _, x in results)
allergens = sum(len(x.allergens) for _, x in results)
review = sum(1 for _, x in results if x.needs_review)
print(f"combine: 주의성분 {cares}개 / 알레르겐 {allergens}개 / 검수대상 {review}건")

if not apply:
    print("\n--- 미리보기 (--apply 를 붙여야 저장합니다) ---")
    for name, res in results[:3]:
        print(f"  {name}")
        for t in res.cares:
            print(f"    CARE     {t.value:12} {t.source.kind}")
        for t in res.allergens:
            print(f"    ALLERGEN {t.value:8} {t.amount}")
    sys.exit(0)

# ── 3. 저장. TaggingService.applyTags 와 같은 순서로 쓴다 ──
now = datetime.now(timezone.utc).replace(tzinfo=None)
with conn.cursor() as cur:
    for name, res in results:
        did = dish_ids[name]
        cur.execute("DELETE FROM dish_tag WHERE dish_id=%s", (did,))
        for kind, tags in (("CARE", res.cares), ("ALLERGEN", res.allergens)):
            for t in tags:
                cur.execute(
                    "INSERT INTO dish_tag"
                    " (dish_id,tag_type,tag_value,amount,source,confidence,evidence,model_id,created_at)"
                    " VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                    (did, kind, t.value, getattr(t, "amount", None), t.source.kind,
                     float(t.source.confidence),
                     json.dumps({"evidence": t.source.evidence}, ensure_ascii=False),
                     MODEL_ID, now),
                )
        facts = nutrition.get(name)
        cur.execute(
            "UPDATE dish SET tagged_at=%s, model_id=%s, needs_review=%s,"
            " nutrition=%s, food_category=%s, nutrition_food_cd=%s WHERE id=%s",
            (now, MODEL_ID, 1 if res.needs_review else 0,
             json.dumps({k.value: v for k, v in facts.values.items()}, ensure_ascii=False)
             if facts else None,
             facts.food_category if facts else None,
             facts.food_code if facts else None,
             did),
        )
conn.commit()
conn.close()
print(f"저장 완료: {len(results)}건")
