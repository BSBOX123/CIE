"""analysis/<배치>.jsonl 이 형식과 어휘를 지키는지, 입력 목록을 빠짐없이 다뤘는지 확인한다.

    python3 tools/tagging/validate_batch.py batch05

입력:  queue/<배치>.txt          태깅할 음식 이름 (한 줄에 하나)
출력:  analysis/<배치>.jsonl     태깅한 음식
       analysis/<배치>.skip.txt  음식 이름이 아니라 태깅하지 않은 것 (한 줄에 하나)

모든 입력 이름이 둘 중 정확히 한 곳에 있어야 한다. DB 에 넣기 전에 반드시 통과시킬 것.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ALLERGENS = {
    "게", "고등어", "난류", "닭고기", "대두", "돼지고기", "땅콩", "메밀", "밀",
    "복숭아", "새우", "쇠고기", "아황산류", "오징어", "우유", "잣", "조개류",
    "토마토", "호두",
}
KEYS = {"n", "i", "a", "c", "p", "pr", "f"}

batch = sys.argv[1]
wanted = [l.rstrip("\n") for l in open(f"{HERE}/queue/{batch}.txt", encoding="utf-8") if l.strip()]
wanted_set = set(wanted)

errors = []
tagged = []
for no, line in enumerate(open(f"{HERE}/analysis/{batch}.jsonl", encoding="utf-8"), 1):
    if not line.strip():
        continue
    try:
        r = json.loads(line)
    except json.JSONDecodeError as e:
        errors.append(f"{no}행 JSON 오류: {e}")
        continue
    if set(r) != KEYS:
        errors.append(f"{no}행 키가 다름: {sorted(r)}")
        continue
    if r["n"] not in wanted_set:
        errors.append(f"{no}행 입력 목록에 없는 이름: {r['n']!r}")
    if not isinstance(r["i"], list) or not all(isinstance(x, str) for x in r["i"]):
        errors.append(f"{no}행 i 는 문자열 목록이어야 함")
    for a in r["a"]:
        if not (isinstance(a, list) and len(a) == 3):
            errors.append(f"{no}행 a 원소는 [값, MAIN|TRACE, 근거]: {a}")
            continue
        if a[0] not in ALLERGENS:
            errors.append(f"{no}행 어휘에 없는 알레르겐: {a[0]!r} ({r['n']})")
        if a[1] not in ("MAIN", "TRACE"):
            errors.append(f"{no}행 amount 는 MAIN/TRACE: {a[1]!r} ({r['n']})")
    if len({a[0] for a in r["a"] if isinstance(a, list) and a}) != len(r["a"]):
        errors.append(f"{no}행 같은 알레르겐이 두 번: {r['n']}")
    if not isinstance(r["c"], bool) or not isinstance(r["p"], bool):
        errors.append(f"{no}행 c/p 는 true/false")
    if not isinstance(r["f"], (int, float)) or not 0 <= r["f"] <= 1:
        errors.append(f"{no}행 f 는 0~1")
    tagged.append(r["n"])

skip_path = f"{HERE}/analysis/{batch}.skip.txt"
skipped = [l.rstrip("\n") for l in open(skip_path, encoding="utf-8") if l.strip()] \
    if os.path.exists(skip_path) else []

dup = {n for n in tagged if tagged.count(n) > 1}
if dup:
    errors.append(f"jsonl 에 두 번 나온 이름: {sorted(dup)[:10]}")
both = set(tagged) & set(skipped)
if both:
    errors.append(f"태깅과 제외에 모두 있음: {sorted(both)[:10]}")
bad_skip = [s for s in skipped if s not in wanted_set]
if bad_skip:
    errors.append(f"skip 에 입력 목록에 없는 이름: {bad_skip[:10]}")
missing = [n for n in wanted if n not in set(tagged) and n not in set(skipped)]
if missing:
    errors.append(f"다루지 않은 이름 {len(missing)}건: {missing[:15]}")

print(f"{batch}: 입력 {len(wanted)} / 태깅 {len(set(tagged))} / 제외 {len(skipped)}")
if errors:
    print(f"오류 {len(errors)}건")
    for e in errors[:40]:
        print("  " + e)
    sys.exit(1)
print("통과")
