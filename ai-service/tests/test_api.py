"""API 엔드포인트 테스트. LLM은 스텁으로 대체한다."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.nutrition.field_map import extract
from app.tagging.models import AllergenAmount, AllergenFinding, LlmDishAnalysis
from app.tagging.vocabulary import Allergen, CareType, Disease

FIXTURES = Path(__file__).parent / "fixtures"


class StubAnalyzer:
    model_id = "stub-model"

    def analyze(self, dish_name: str, food_category: str | None = None):
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


class StubNutrition:
    async def lookup(self, dish_name: str, *, rows: int = 100):
        return None


@pytest.fixture
def client():
    with TestClient(app) as c:
        c.app.state.analyzer = StubAnalyzer()
        c.app.state.nutrition = StubNutrition()
        yield c


def test_health(client):
    body = client.get("/health").json()
    assert body["status"] == "ok"


def test_vocabulary_는_스펙_어휘를_그대로_노출한다(client):
    """백엔드·프론트가 어휘를 각자 하드코딩하면 철자 불일치로 판정이 죽는다."""
    body = client.get("/vocabulary").json()
    assert len(body["allergens"]) == 19
    assert len(body["cares"]) == 7
    assert len(body["diseases"]) == 5
    assert "아황산류" in body["allergens"]
    assert "잣" in body["allergens"]
    assert body["diseaseMap"]["만성콩팥병"] == ["나트륨", "칼륨", "단백질량"]


def test_모든_질환이_매핑을_가진다(client):
    body = client.get("/vocabulary").json()
    assert set(body["diseaseMap"]) == {d.value for d in Disease}


def test_tag_dish_영양성분_직접_전달(client):
    facts = extract(json.loads((FIXTURES / "kimchi_jjigae.json").read_text("utf-8")))
    resp = client.post("/tag/dish", json={
        "dish_name": "김치찌개",
        "food_category": "찌개 및 전골류",
        "nutrition": {k.value: v for k, v in facts.items()},
    })
    assert resp.status_code == 200
    body = resp.json()
    care_values = {c["value"] for c in body["cares"]}
    assert CareType.SODIUM.value in care_values
    assert body["model_id"] == "stub-model"


def test_tag_dish_영양성분_없어도_실패하지_않는다(client):
    """DB 매칭이 안 되면 LLM 추정만으로 태깅하고 검수 대상으로 둔다."""
    resp = client.post("/tag/dish", json={"dish_name": "존재하지않는음식"})
    assert resp.status_code == 200
    assert resp.json()["needs_review"] is True


def test_빈_음식명은_거부한다(client):
    assert client.post("/tag/dish", json={"dish_name": ""}).status_code == 422


def test_응답에_판정이_없다(client):
    """판정은 백엔드 규칙 엔진 몫이다 (SPEC 8.2)."""
    resp = client.post("/tag/dish", json={"dish_name": "김치찌개"})
    assert "verdict" not in resp.json()
