"""로컬 색인이 우리 음식 이름을 얼마나 맞추는지 재 본다. DB 도 API 도 건드리지 않는다.

    ai-service/.venv/bin/python tools/nutrition/measure.py [표본수]

태깅 자산(tools/tagging/analysis/*.jsonl)의 음식 이름을 그대로 쓴다. 등급별
건수와 무작위 표본을 함께 찍어, 넓힌 규칙이 엉뚱한 음식을 붙이지 않는지 눈으로
확인한다 — 매칭률만 보면 "삼겹살"을 아무 데나 붙여도 숫자는 올라간다.
"""
from __future__ import annotations

import glob
import gzip
import json
import os
import random
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "ai-service"))

from app.nutrition.match import NutritionIndex  # noqa: E402

sample_size = int(sys.argv[1]) if len(sys.argv) > 1 else 25

names: list[str] = []
for path in sorted(glob.glob(f"{ROOT}/tools/tagging/analysis/*.jsonl")):
    names.extend(json.loads(line)["n"] for line in open(path, encoding="utf-8") if line.strip())
names = list(dict.fromkeys(names))

with gzip.open(f"{HERE}/data/foods.jsonl.gz", "rt", encoding="utf-8") as f:
    index = NutritionIndex(json.loads(line) for line in f)

hits: list[tuple[str, str, int]] = []
ranks: Counter[int] = Counter()
for name in names:
    match = index.find(name)
    if match is None:
        ranks["없음"] += 1
        continue
    ranks[match.rank] += 1
    hits.append((name, match.record.get("FOOD_NM_KR", ""), match.rank))

matched = len(hits)
print(f"음식 {len(names):,}건 중 {matched:,}건 매칭 ({matched / len(names):.0%})")
label = {0: "0 정확", 1: "1 통용명", 2: "2 수식 제거", 3: "3 DB 이름이 더 김"}
for rank, count in sorted(ranks.items(), key=lambda kv: str(kv[0])):
    print(f"  {label.get(rank, rank)}: {count:,}")

random.seed(11)
print(f"\n무작위 표본 {sample_size}건 — 엉뚱하게 붙은 것이 없는지 볼 것")
for name, db_name, rank in random.sample(hits, min(sample_size, len(hits))):
    print(f"  [{rank}] {name}  →  {db_name}")

missed = [n for n in names if index.find(n) is None]
print(f"\n못 맞춘 것 표본 {min(15, len(missed))}건")
for name in random.sample(missed, min(15, len(missed))):
    print(f"  {name}")
