"""Gemini(Google AI Studio) 기반 음식 분석.

**LLM이 하는 일** — 음식명으로부터 재료·알레르겐을 추정하고, 영양성분DB로
판정할 수 없는 두 성분(정제 탄수화물, 퓨린)을 분류한다.

**LLM이 하지 않는 일** — 최종 판정(RED/INK/OK). 사용자 프로필과의 대조는
서버 규칙 엔진이 한다 (SPEC 8.2).

알레르겐 추정은 누락이 오탐보다 위험하므로 보수적으로 — "들어갈 가능성이
있으면 포함"하도록 지시한다. 그 대신 신뢰도를 함께 받아 화면에 '추정'으로
표시한다.
"""

from __future__ import annotations

from google import genai

from app.config import settings
from app.tagging.models import LlmDishAnalysis
from app.tagging.schema import to_output_schema
from app.tagging.vocabulary import Allergen

_ALLERGEN_LIST = " · ".join(a.value for a in Allergen)

SYSTEM_PROMPT = f"""당신은 한국 음식의 재료를 분석하는 도우미입니다.

음식 이름을 받으면 그 음식에 **일반적으로** 들어가는 재료를 판단합니다.
이 정보는 지병이 있는 사람이 외식할 때 참고하는 서비스에 쓰입니다.

## 알레르기 유발물질 (식약처 표시 대상 19종)
{_ALLERGEN_LIST}

## 판단 원칙

1. **알레르겐은 보수적으로 판단합니다.** 들어갈 가능성이 상당하면 포함하세요.
   빠뜨리는 것이 잘못 넣는 것보다 위험합니다.

2. **알레르겐마다 들어가는 정도를 반드시 구분합니다.**

   - `MAIN` — **주재료**입니다. 빼면 그 음식이 아니게 됩니다.
     예: 물회의 `새우`, 소금빵의 `우유`, 칼국수 면의 `밀`, 두부의 `대두`
   - `TRACE` — **양념이나 부재료에 미량** 들어갑니다. 빼달라고 요청할 여지가
     있습니다.
     예: 간장으로 간을 맞춘 국의 `밀`·`대두`, 된장의 `대두`,
     김치에 들어간 젓갈의 `새우`·`조개류`

   이 구분이 가장 중요합니다. 국물에 간장이 들어간다는 이유로 `밀`을 MAIN으로
   분류하면, 밀 알레르기가 있는 사람에게 거의 모든 한국 음식이 금지됩니다.
   **면·빵·튀김옷처럼 밀이 음식의 실체를 이룰 때만 MAIN입니다.**

3. **조리법을 근거로 삼습니다.** 이름에 드러나지 않아도 표준 조리법에 들어가는
   재료를 포함하세요. 예: 김치에는 젓갈이 들어가므로 `새우`·`조개류`가 TRACE로
   걸릴 수 있습니다.

4. **확신이 낮으면 confidence를 낮게 매깁니다.** 지역마다 조리법이 다르거나
   이름만으로 재료를 알기 어려우면 0.5 이하로 주세요.

5. **먹어도 되는지 판정하지 마세요.** 재료가 무엇인지만 답합니다. 판정은
   다른 곳에서 사용자별로 계산합니다."""


def build_prompt(dish_name: str, food_category: str | None = None) -> str:
    """사용자 메시지 본문.

    단건 호출과 대량 태깅이 **같은 프롬프트를 써야 한다.** 갈라지면 같은 음식이
    경로에 따라 다른 태그를 받는다.
    """
    lines = [f"음식 이름: {dish_name}"]
    if food_category:
        lines.append(f"식품군: {food_category}")
    lines.append("")
    lines.append("이 음식의 재료를 분석해 주세요.")
    return "\n".join(lines)


class DishAnalyzer:
    """음식명 -> 재료·알레르겐 구조화 분석."""

    def __init__(self, client: genai.Client | None = None) -> None:
        # 클라이언트를 여기서 만들지 않는다. 키가 없어도 서비스는 떠야 한다 —
        # /vocabulary 와 /nutrition/lookup 은 LLM 없이 동작하고, 키 설정 여부는
        # /health 가 알려준다.
        self._client = client
        self._model = settings.llm_model_id
        # Pydantic 기본 스키마는 $ref/$defs 를 쓴다. 평탄화한 쪽이 어느
        # 제공자에서든 안전하다 (app/tagging/schema.py 참조).
        self._schema = to_output_schema(LlmDishAnalysis)

    @property
    def model_id(self) -> str:
        return self._model

    @property
    def client(self) -> genai.Client:
        """첫 사용 시점에 만든다. SDK가 GEMINI_API_KEY 를 자동으로 읽는다."""
        if self._client is None:
            self._client = genai.Client()
        return self._client

    def analyze(
        self, dish_name: str, food_category: str | None = None
    ) -> LlmDishAnalysis:
        """음식 1건을 분석한다. 실패는 예외로 올린다 — 호출부가 판단한다."""
        interaction = self.client.interactions.create(
            model=self._model,
            system_instruction=SYSTEM_PROMPT,
            input=build_prompt(dish_name, food_category),
            response_format={
                "type": "text",
                "mime_type": "application/json",
                "schema": self._schema,
            },
        )
        return LlmDishAnalysis.model_validate_json(interaction.output_text)
