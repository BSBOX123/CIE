"""Pydantic 모델 -> Anthropic 구조화 출력 스키마 변환.

``messages.parse()`` 는 단건 호출에서 이 변환을 알아서 해 주지만, Batch API로
보낼 때는 ``output_config.format`` 에 넣을 JSON Schema를 직접 만들어야 한다.

Anthropic 구조화 출력이 요구하는 것:

* 모든 object에 ``additionalProperties: false``
* 모든 속성이 ``required``
* ``$ref``/``$defs`` 없이 펼쳐진 형태

Pydantic이 만드는 스키마는 셋 다 만족하지 않으므로 여기서 맞춰 준다.
"""

from __future__ import annotations

import copy
from typing import Any

from pydantic import BaseModel


def _resolve(node: Any, defs: dict[str, Any], seen: frozenset[str]) -> Any:
    """``$ref`` 를 실제 정의로 치환한다."""
    if isinstance(node, list):
        return [_resolve(item, defs, seen) for item in node]
    if not isinstance(node, dict):
        return node

    ref = node.get("$ref")
    if isinstance(ref, str) and ref.startswith("#/$defs/"):
        name = ref.removeprefix("#/$defs/")
        if name in seen:
            # 재귀 정의는 펼칠 수 없다. 스키마를 조용히 깨뜨리는 대신 알린다.
            raise ValueError(f"재귀 참조는 구조화 출력에 쓸 수 없습니다: {name}")
        if name not in defs:
            raise ValueError(f"참조 대상을 찾을 수 없습니다: {ref}")
        merged = _resolve(copy.deepcopy(defs[name]), defs, seen | {name})
        # $ref 옆에 있던 다른 키(description 등)를 보존한다.
        for key, value in node.items():
            if key != "$ref":
                merged[key] = _resolve(value, defs, seen)
        return merged

    return {key: _resolve(value, defs, seen) for key, value in node.items()}


def _tighten(node: Any) -> Any:
    """object마다 additionalProperties=false 와 전체 required 를 채운다."""
    if isinstance(node, list):
        return [_tighten(item) for item in node]
    if not isinstance(node, dict):
        return node

    out = {key: _tighten(value) for key, value in node.items()}
    if out.get("type") == "object" and isinstance(out.get("properties"), dict):
        out["additionalProperties"] = False
        out["required"] = list(out["properties"].keys())
    return out


def to_output_schema(model: type[BaseModel]) -> dict[str, Any]:
    """Pydantic 모델을 Anthropic ``output_config.format`` 스키마로 변환."""
    raw = model.model_json_schema()
    defs = raw.pop("$defs", {})
    resolved = _resolve(raw, defs, frozenset())
    tightened = _tighten(resolved)
    # title 은 모델에 의미가 없고 토큰만 쓴다.
    tightened.pop("title", None)
    return tightened


def output_format(model: type[BaseModel]) -> dict[str, Any]:
    """``messages.create(output_config=...)`` 에 그대로 넣을 값."""
    return {"format": {"type": "json_schema", "schema": to_output_schema(model)}}
