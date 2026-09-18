# 태깅 도구

`dish` 에 알레르겐·주의성분을 붙인다. 이게 있어야 판정(○△✕)이 나온다.

## 두 갈래

태깅은 두 출처를 합치는 작업이다.

| | 출처 | 담당 |
|---|---|---|
| 알레르겐 19종 | LLM | 음식명 → 재료 추론 |
| 정제 탄수화물 · 퓨린 | LLM | 영양성분DB에 항목 자체가 없다 |
| 나트륨 · 칼륨 · 당류 · 포화지방 · 단백질량 | 식약처 영양성분DB | 실측값을 1회 섭취량 기준 임계값과 비교 |

`app.tagging.service.combine()` 이 이 둘을 합친다. **판정(RED/INK/OK)은 LLM 이
하지 않는다** — 서버 규칙 엔진이 사용자 프로필과 교집합을 내서 정한다.

## 평소 경로 — Gemini 배치

`TAGGING_SCHEDULED=true` 로 켜면 30분마다 미태깅 큐를 줄인다. 새로 들어온
음식은 이쪽이 알아서 처리한다.

무료 티어가 **분당 15건**이라 한 번에 많이 하지는 못한다. 초기 적재처럼
수천 건을 밀어 넣어야 할 때는 아래 도구를 쓴다.

## 대량 처리 — 이 도구

사람(또는 더 빠른 모델)이 `analysis/*.jsonl` 을 만들어 두면, 그것을 Gemini
응답 대신 파이프라인에 먹인다. 영양성분 조회와 판정 로직은 그대로 돈다.

```bash
# SSM 터널을 먼저 연다 (다른 탭)
aws ssm start-session --region ap-northeast-2 --target i-0bd8ffad33886895e \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["127.0.0.1"],"portNumber":["3307"],"localPortNumber":["3308"]}'

export PUBLIC_DATA_API_KEY=$(grep '^PUBLIC_DATA_API_KEY=' ai-service/.env | cut -d= -f2-)
export DB_PASSWORD=...   # 서버 ~/CIE/.env

# 미리보기
ai-service/.venv/bin/python tools/tagging/apply_tags.py batch01
# 저장
ai-service/.venv/bin/python tools/tagging/apply_tags.py batch01 --apply
```

## jsonl 형식

한 줄이 음식 하나다.

```json
{"n":"초당순두부","i":["순두부","간수","대파"],
 "a":[["대두","MAIN","순두부의 주원료"],["밀","TRACE","곁들이는 간장 양념"]],
 "c":false,"p":false,"pr":"콩 위주로 퓨린이 특별히 높지 않다","f":0.93}
```

| 키 | 뜻 |
|---|---|
| `n` | 음식명. `dish.normalized_name` 과 **정확히** 같아야 한다 |
| `i` | 주요 재료 |
| `a` | 알레르겐 `[[값, MAIN\|TRACE, 근거], ...]` |
| `c` | 정제 탄수화물이 주가 되는 음식인지 |
| `p` | 퓨린이 높은지 |
| `pr` | 퓨린 판단 근거 |
| `f` | 재료 구성에 대한 신뢰도 0~1 |

## MAIN 과 TRACE 를 나누는 이유

간장·된장·고추장 때문에 밀과 대두가 과도하게 붙는 문제가 있었다. 실측에서
밀이 19/29 건에 붙어 밀 알레르기 사용자가 쓸 수 있는 메뉴가 **34%** 였다.

- `MAIN` — 주재료다. 빼면 그 음식이 아니다 → **✕**
- `TRACE` — 양념에 미량. 빼달라고 요청할 여지가 있다 → **△**

간장에서 오는 밀은 `TRACE`, 순두부의 대두는 `MAIN` 이다. 다만 간장 양념에
재우는 불고기·갈비찜처럼 간장이 실제로 많이 들어가는 음식은 `MAIN` 이 맞다.

## 주의

- **어휘를 벗어난 값을 쓰면 안 된다.** 19종/7종 밖의 값은 사용자 프로필과
  교집합이 나지 않아 판정이 조용히 실패한다. 스크립트가 저장 전에 막는다.
- `model_id` 에 누가 태깅했는지 남는다. Gemini 배치와 섞여도 구분된다.
- 퓨린은 영양성분DB에 항목이 없어 신뢰도 상한이 0.5 다. 검수 임계값 0.6 보다
  낮으므로 **퓨린이 붙은 음식은 자동으로 검수 대상**이 된다. 정상 동작이다.
