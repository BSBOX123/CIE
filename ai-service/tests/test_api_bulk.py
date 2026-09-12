"""대량 태깅 엔드포인트 테스트."""

from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.nutrition.field_map import extract
from app.tagging.bulk import DishOutcome
from app.tagging.models import AllergenAmount, AllergenFinding, LlmDishAnalysis
from app.tagging.vocabulary import Allergen, CareType

FIXTURES = Path(__file__).parent / "fixtures"


def facts(name: str) -> dict[str, float | None]:
    values = extract(json.loads((FIXTURES / f"{name}.json").read_text("utf-8")))
    return {k.value: v for k, v in values.items()}


def analysis() -> LlmDishAnalysis:
    return LlmDishAnalysis(
        ingredients=["돼지고기", "김치"],
        allergens=[
            AllergenFinding(allergen=Allergen.PORK, amount=AllergenAmount.MAIN,
                            reason="돼지고기가 주재료입니다"),
            AllergenFinding(allergen=Allergen.SOY, amount=AllergenAmount.TRACE,
                            reason="된장으로 간을 맞춥니다"),
        ],
        is_refined_carb_staple=False,
        is_high_purine=True,
        purine_reasoning="육수를 진하게 냅니다.",
        confidence=0.85,
    )


class StubBulk:
    model_id = "gemini-3.8-flash"

    def __init__(self, outcomes=None):
        self._outcomes = outcomes
        self.received = None

    def tag(self, requests):
        self.received = requests
        if self._outcomes is not None:
            return self._outcomes
        return [DishOutcome(r.dish_id, analysis()) for r in requests]


class StubNutrition:
    async def lookup(self, dish_name, *, rows=100):
        if dish_name == "없는음식":
            return None
        return SimpleNamespace(
            food_code="CD1",
            food_name=dish_name,
            source_kind="외식(분석 함량)",
            values=extract(json.loads((FIXTURES / "kimchi_jjigae.json").read_text("utf-8"))),
        )


@pytest.fixture
def client():
    with TestClient(app) as c:
        c.app.state.bulk = StubBulk()
        c.app.state.nutrition = StubNutrition()
        yield c


def test_영양성분과_LLM결과가_합쳐진다(client):
    resp = client.post("/tag/dishes", json={
        "dishes": [{
            "dish_id": "1", "dish_name": "김치찌개",
            "food_category": "찌개 및 전골류", "nutrition": facts("kimchi_jjigae"),
        }]
    })

    body = resp.json()
    assert body["failed"] == []
    cares = {c["value"]: c["source"]["kind"] for c in body["tagged"][0]["cares"]}
    assert cares[CareType.SODIUM.value] == "NUTRITION_DB"   # 수치 근거
    assert cares[CareType.PURINE.value] == "LLM"            # DB에 항목 없음
    assert body["model_id"] == "gemini-3.8-flash"


def test_여러_건을_한_번에_처리한다(client):
    resp = client.post("/tag/dishes", json={
        "dishes": [
            {"dish_id": "1", "dish_name": "김치찌개"},
            {"dish_id": "2", "dish_name": "물회"},
            {"dish_id": "3", "dish_name": "된장찌개"},
        ]
    })
    assert len(resp.json()["tagged"]) == 3


def test_실패한_건은_failed로_보고된다(client):
    """조용히 빠지면 태그 없는 음식이 '안전'으로 보인다."""
    client.app.state.bulk = StubBulk([DishOutcome("1", None, error="429 rate limit")])

    body = client.post("/tag/dishes", json={
        "dishes": [{"dish_id": "1", "dish_name": "김치찌개"}]
    }).json()

    assert body["tagged"] == []
    assert body["failed"][0]["dish_id"] == "1"
    assert "429" in body["failed"][0]["error"]


def test_일부_성공_일부_실패(client):
    client.app.state.bulk = StubBulk([
        DishOutcome("1", analysis()),
        DishOutcome("2", None, error="timeout"),
    ])

    body = client.post("/tag/dishes", json={
        "dishes": [
            {"dish_id": "1", "dish_name": "김치찌개"},
            {"dish_id": "2", "dish_name": "물회"},
        ]
    }).json()

    assert len(body["tagged"]) == 1
    assert len(body["failed"]) == 1


def test_요청에_없는_dish_id는_실패로_남긴다(client):
    """조용히 버리면 왜 태그가 비었는지 추적할 수 없다."""
    client.app.state.bulk = StubBulk([DishOutcome("999", analysis())])

    body = client.post("/tag/dishes", json={
        "dishes": [{"dish_id": "1", "dish_name": "김치찌개"}]
    }).json()

    assert body["failed"][0]["error"] == "요청 목록에 없는 dish_id"


def test_영양성분이_없어도_태깅된다(client):
    """DB 매칭 실패는 정상 경로다. LLM 추정만으로 태깅하고 검수 대상이 된다."""
    body = client.post("/tag/dishes", json={
        "dishes": [{"dish_id": "1", "dish_name": "듣도보도못한음식"}]
    }).json()

    assert body["tagged"][0]["needs_review"] is True


def test_빈_목록은_거부한다(client):
    assert client.post("/tag/dishes", json={"dishes": []}).status_code == 422


def test_한_번에_보낼_수_있는_수에_상한이_있다(client):
    """무료 티어의 일일 한도를 한 번에 태워버리지 않도록."""
    dishes = [{"dish_id": str(i), "dish_name": f"음식{i}"} for i in range(201)]
    assert client.post("/tag/dishes", json={"dishes": dishes}).status_code == 422


def test_영양성분_일괄조회(client):
    body = client.post("/nutrition/lookup", json=["김치찌개", "된장찌개"]).json()
    assert body["matched"] == 2
    assert body["results"]["김치찌개"]["values"]["나트륨"] == 491.0


def test_매칭_실패는_결과에서_빠진다(client):
    body = client.post("/nutrition/lookup", json=["김치찌개", "없는음식"]).json()
    assert body["requested"] == 2
    assert body["matched"] == 1


def test_키가_없어도_서비스는_뜬다(client):
    """LLM 키가 없어도 어휘·영양성분 조회는 동작해야 한다."""
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert "gemini_key_configured" in body
