"""구조화 출력 스키마 변환 테스트."""

from __future__ import annotations

import pytest
from pydantic import BaseModel, Field

from app.tagging.models import LlmDishAnalysis
from app.tagging.schema import output_format, to_output_schema


def test_ref_가_펼쳐진다():
    """$ref/$defs 가 남아 있으면 Anthropic이 스키마를 거부한다."""
    schema = to_output_schema(LlmDishAnalysis)
    assert "$defs" not in schema
    assert "$ref" not in str(schema)


def test_알레르겐_열거값이_인라인된다():
    """allergens 는 이제 {allergen, amount, reason} 객체 배열이다."""
    schema = to_output_schema(LlmDishAnalysis)
    finding = schema["properties"]["allergens"]["items"]
    assert finding["additionalProperties"] is False
    assert len(finding["properties"]["allergen"]["enum"]) == 19
    assert "아황산류" in finding["properties"]["allergen"]["enum"]


def test_알레르겐_정도_열거값이_인라인된다():
    schema = to_output_schema(LlmDishAnalysis)
    amount = schema["properties"]["allergens"]["items"]["properties"]["amount"]
    assert amount["enum"] == ["MAIN", "TRACE"]


def test_additionalProperties가_false다():
    schema = to_output_schema(LlmDishAnalysis)
    assert schema["additionalProperties"] is False


def test_모든_속성이_required다():
    schema = to_output_schema(LlmDishAnalysis)
    assert set(schema["required"]) == set(schema["properties"])


def test_description은_보존된다():
    """모델이 필드 의미를 알아야 하므로 설명은 남긴다."""
    schema = to_output_schema(LlmDishAnalysis)
    assert schema["properties"]["is_high_purine"]["description"]


def test_중첩_object도_처리된다():
    class Inner(BaseModel):
        a: str

    class Outer(BaseModel):
        inner: Inner
        items: list[Inner] = Field(default_factory=list)

    schema = to_output_schema(Outer)
    assert schema["properties"]["inner"]["additionalProperties"] is False
    assert schema["properties"]["items"]["items"]["additionalProperties"] is False


def test_재귀_모델은_조용히_깨지지_않고_알린다():
    class Node(BaseModel):
        child: "Node | None" = None

    Node.model_rebuild()
    with pytest.raises(ValueError, match="재귀"):
        to_output_schema(Node)


def test_output_format_형태():
    fmt = output_format(LlmDishAnalysis)
    assert fmt["format"]["type"] == "json_schema"
    assert "schema" in fmt["format"]
