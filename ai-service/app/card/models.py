"""주문요청카드 문구 다듬기 DTO (SPEC 9.5)."""

from __future__ import annotations

from pydantic import BaseModel, Field


class PolishRequest(BaseModel):
    """다듬을 요청 문구들.

    ``must_keep`` 은 문구에서 **절대 사라지면 안 되는 낱말**이다(알레르겐 이름
    등). 카드는 매장 직원에게 건네는 물건이라, 다듬는 과정에서 '새우'가 빠지면
    알레르기 환자에게 위험하다.
    """

    phrases: list[str] = Field(min_length=1, max_length=20)
    must_keep: list[str] = Field(default_factory=list)
    menu_label: str | None = None


class PolishedPhrase(BaseModel):
    original: str
    polished: str
    #: 안전 검사를 통과하지 못해 원문을 그대로 쓴 경우
    fell_back: bool = False


class PolishResponse(BaseModel):
    phrases: list[PolishedPhrase]
    model_id: str | None = None
