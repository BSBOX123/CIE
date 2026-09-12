"""주문요청카드 문구 다듬기.

**LLM의 역할은 표현을 다듬는 것뿐이다.** 무엇을 요청할지는 서버가 이미 정했다
(SPEC 8.2). 여기서 하는 일은 자동 생성된 문구("대두는 빼 주세요")를 매장에
건네기 자연스러운 말로 바꾸는 것이다.

**의미가 바뀌면 위험하다.** 카드는 알레르기를 알리는 물건이라, 다듬는 과정에서
'새우'가 사라지면 사람이 다칠 수 있다. 그래서 결과를 그대로 믿지 않고 검사한다:

* 원문 개수와 결과 개수가 같아야 한다
* ``must_keep`` 의 낱말이 원문에 있었다면 결과에도 있어야 한다

하나라도 어긋나면 그 문구는 **원문을 그대로 쓴다.** 다듬기는 있으면 좋은
것이지 반드시 필요한 것이 아니다.
"""

from __future__ import annotations

import logging

from google import genai

from app.card.models import PolishedPhrase, PolishRequest, PolishResponse
from app.config import settings
from app.tagging.schema import to_output_schema
from pydantic import BaseModel, Field

log = logging.getLogger(__name__)

SYSTEM_PROMPT = """당신은 식당에 건네는 주문 요청 문구를 다듬습니다.

지병이 있는 손님이 종이 카드를 매장 직원에게 보여줍니다. 문구는 짧고,
공손하고, 한눈에 읽혀야 합니다.

## 규칙

1. **재료 이름을 절대 바꾸거나 빼지 마세요.** '새우'를 '해산물'로 바꾸면
   안 됩니다. 손님이 다칠 수 있습니다.
2. 요청의 뜻을 바꾸지 마세요. 강도를 낮추거나("가능하면") 조건을 붙이지
   마세요.
3. 짧게 씁니다. 한 문장, 20자 내외.
4. 정중한 요청체로 끝냅니다.
5. 문구 개수를 그대로 유지하세요. 합치거나 나누지 마세요."""


class _PolishedList(BaseModel):
    """LLM 구조화 출력."""

    phrases: list[str] = Field(description="입력과 같은 개수, 같은 순서로 다듬은 문구")


class PhrasePolisher:
    def __init__(self, client: genai.Client | None = None) -> None:
        self._client = client
        self._model = settings.llm_model_id
        self._schema = to_output_schema(_PolishedList)

    @property
    def model_id(self) -> str:
        return self._model

    @property
    def client(self) -> genai.Client:
        if self._client is None:
            self._client = genai.Client()
        return self._client

    def polish(self, request: PolishRequest) -> PolishResponse:
        """문구를 다듬는다. 실패하거나 검사에 걸리면 원문을 쓴다."""
        try:
            polished = self._ask(request)
        except Exception as exc:  # noqa: BLE001 - 제공자 예외가 다양하다
            log.warning("문구 다듬기 실패, 원문을 씁니다: %s", exc)
            polished = None

        return PolishResponse(
            phrases=self._verify(request, polished),
            model_id=self._model if polished else None,
        )

    def _ask(self, request: PolishRequest) -> list[str]:
        lines = []
        if request.menu_label:
            lines.append(f"주문할 메뉴: {request.menu_label}")
        lines.append("다듬을 문구:")
        lines.extend(f"{i + 1}. {p}" for i, p in enumerate(request.phrases))
        if request.must_keep:
            lines.append("")
            lines.append("반드시 그대로 남겨야 할 낱말: " + ", ".join(request.must_keep))

        interaction = self.client.interactions.create(
            model=self._model,
            system_instruction=SYSTEM_PROMPT,
            input="\n".join(lines),
            response_format={
                "type": "text",
                "mime_type": "application/json",
                "schema": self._schema,
            },
        )
        return _PolishedList.model_validate_json(interaction.output_text).phrases

    def _verify(
        self, request: PolishRequest, polished: list[str] | None
    ) -> list[PolishedPhrase]:
        """검사를 통과한 것만 채택하고, 나머지는 원문을 쓴다."""
        if polished is None or len(polished) != len(request.phrases):
            if polished is not None:
                log.warning(
                    "문구 개수가 달라 전부 원문을 씁니다: %d -> %d",
                    len(request.phrases), len(polished),
                )
            return [PolishedPhrase(original=p, polished=p, fell_back=True)
                    for p in request.phrases]

        out: list[PolishedPhrase] = []
        for original, candidate in zip(request.phrases, polished, strict=True):
            if self._keeps_terms(original, candidate, request.must_keep):
                out.append(PolishedPhrase(original=original, polished=candidate.strip()))
            else:
                log.warning("낱말이 사라져 원문을 씁니다: %r -> %r", original, candidate)
                out.append(
                    PolishedPhrase(original=original, polished=original, fell_back=True)
                )
        return out

    @staticmethod
    def _keeps_terms(original: str, candidate: str, must_keep: list[str]) -> bool:
        if not candidate or not candidate.strip():
            return False
        for term in must_keep:
            # 원문에 있던 낱말은 결과에도 있어야 한다.
            if term in original and term not in candidate:
                return False
        return True
