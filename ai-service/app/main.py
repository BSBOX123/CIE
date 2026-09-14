"""먹어도 돼? — AI 태깅 서비스.

백엔드 배치가 호출하는 내부 서비스다 (SPEC 5.1). 사용자 요청 경로에서
직접 호출하지 않는다 — 판정 재현성을 위해 태깅은 사전 배치로만 한다
(SPEC 7.4).
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import Body, FastAPI, HTTPException
from pydantic import BaseModel, Field

from app.config import settings
from app.card.models import PolishRequest, PolishResponse
from app.card.polish import PhrasePolisher
from app.nutrition.client import NutritionClient
from app.nutrition.field_map import Nutrient
from app.tagging.bulk import BulkTagger, DishRequest
from app.tagging.llm import DishAnalyzer
from app.tagging.models import DishTagRequest, DishTagResponse
from app.tagging.service import combine
from app.tagging.vocabulary import CARE_NOTES, DISEASE_MAP, Allergen, CareType, Disease


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    app.state.nutrition = NutritionClient()
    app.state.analyzer = DishAnalyzer()
    app.state.bulk = BulkTagger()
    app.state.polisher = PhrasePolisher()
    yield


app = FastAPI(
    title="먹어도 돼? AI 태깅 서비스",
    version="0.1.0",
    lifespan=lifespan,
)


@app.get("/health")
async def health() -> dict[str, object]:
    return {
        "status": "ok",
        "model": settings.llm_model_id,
        "public_data_key_configured": bool(settings.public_data_api_key),
        "gemini_key_configured": bool(settings.gemini_api_key),
    }


@app.get("/vocabulary")
async def vocabulary() -> dict[str, object]:
    """백엔드·프론트가 어휘를 하드코딩하지 않도록 노출한다 (SPEC 2장).

    철자가 한 글자만 달라도 판정이 조용히 실패하므로(교집합이 빈 집합이 됨),
    한 곳에서만 정의한다.
    """
    return {
        "diseases": [d.value for d in Disease],
        "cares": [c.value for c in CareType],
        "allergens": [a.value for a in Allergen],
        "diseaseMap": {d.value: [c.value for c in cs] for d, cs in DISEASE_MAP.items()},
        "careNotes": {c.value: note for c, note in CARE_NOTES.items()},
    }


@app.post("/tag/dish", response_model=DishTagResponse)
async def tag_dish(request: DishTagRequest) -> DishTagResponse:
    """정규화된 음식명 1건을 태깅한다.

    ``nutrition`` 이 없으면 서비스가 직접 영양성분DB를 조회한다. 조회에
    실패해도 실패로 처리하지 않는다 — LLM 추정만으로 태깅하되 신뢰도가
    낮아져 검수 대상이 된다.
    """
    nutrition = request.nutrition
    food_category = request.food_category

    if nutrition is None:
        facts = await app.state.nutrition.lookup(request.dish_name)
        if facts is not None:
            nutrition = facts.values

    try:
        analysis = app.state.analyzer.analyze(request.dish_name, food_category)
    except Exception as exc:  # noqa: BLE001 - 제공자 예외 종류가 다양하다
        raise HTTPException(status_code=502, detail=f"LLM 분석 실패: {exc}") from exc

    enriched = request.model_copy(
        update={"nutrition": nutrition, "food_category": food_category}
    )
    return combine(enriched, analysis, app.state.analyzer.model_id)


# ── 대량 태깅 (SPEC 7.1 [5] TAG) ────────────────────────────────────
# Google AI Studio 무료 티어에서는 Batch API를 쓸 수 없다(2026-09 확인).
# 그래서 분당 요청 수(RPM)에 맞춰 간격을 두고 건별로 호출한다.
#
# 무료 티어는 일일 요청 수(RPD)도 제한하므로 한 번에 전부 처리하려 들면
# 중간에 막힌다. 이 엔드포인트는 "주어진 만큼만" 처리하고, 남은 음식은 다음
# 호출이 가져간다. 재개 지점은 백엔드의 미태깅 큐가 관리한다.


class BulkDish(BaseModel):
    dish_id: str = Field(min_length=1, max_length=50)
    dish_name: str = Field(min_length=1, max_length=100)
    food_category: str | None = None
    #: 백엔드가 dish.nutrition 에 보관하던 값. 없으면 LLM 추정만으로 태깅한다.
    nutrition: dict[Nutrient, float | None] | None = None


class BulkTagRequest(BaseModel):
    dishes: list[BulkDish] = Field(min_length=1, max_length=200)


class BulkFailure(BaseModel):
    dish_id: str
    error: str


class BulkTagResponse(BaseModel):
    model_id: str
    tagged: list[DishTagResponse]
    failed: list[BulkFailure]


@app.post("/nutrition/lookup")
async def nutrition_lookup(names: list[str] = Body(..., min_length=1)) -> dict[str, object]:
    """음식명 여러 건의 영양성분을 조회한다.

    매칭에 실패한 이름은 결과에서 빠진다. 실패가 아니라 정상 경로다 —
    영양성분DB에 없는 음식은 LLM 추정만으로 태깅하고 검수 대상이 된다.
    """
    found: dict[str, object] = {}
    for name in names:
        facts = await app.state.nutrition.lookup(name)
        if facts is not None:
            found[name] = {
                "food_code": facts.food_code,
                "food_name": facts.food_name,
                "source_kind": facts.source_kind,
                # 백엔드가 dish.food_category 에 저장했다가 태깅 때 되돌려준다.
                # 이게 없으면 1회 섭취량이 기본값으로 고정된다.
                "food_category": facts.food_category,
                "values": {k.value: v for k, v in facts.values.items()},
            }
    return {"requested": len(names), "matched": len(found), "results": found}


@app.post("/tag/dishes", response_model=BulkTagResponse)
def tag_dishes(request: BulkTagRequest) -> BulkTagResponse:
    """여러 음식을 태깅한다.

    RPM 한도를 지키느라 요청당 수 분이 걸릴 수 있다. FastAPI가 동기 함수를
    워커 스레드에서 돌리므로 다른 요청을 막지는 않는다.
    """
    requests = [
        DishRequest(d.dish_id, d.dish_name, d.food_category) for d in request.dishes
    ]
    outcomes = app.state.bulk.tag(requests)

    by_id = {d.dish_id: d for d in request.dishes}
    model_id = app.state.bulk.model_id
    tagged: list[DishTagResponse] = []
    failed: list[BulkFailure] = []

    for outcome in outcomes:
        dish = by_id.get(outcome.dish_id)
        if dish is None:
            failed.append(
                BulkFailure(dish_id=outcome.dish_id, error="요청 목록에 없는 dish_id")
            )
            continue
        if not outcome.succeeded:
            failed.append(BulkFailure(dish_id=outcome.dish_id, error=outcome.error or "실패"))
            continue
        tag_request = DishTagRequest(
            dish_name=dish.dish_name,
            food_category=dish.food_category,
            nutrition=dish.nutrition,
        )
        tagged.append(combine(tag_request, outcome.analysis, model_id))

    return BulkTagResponse(model_id=model_id, tagged=tagged, failed=failed)


# ── 주문요청카드 문구 다듬기 (SPEC 9.5) ────────────────────────────
# LLM 은 표현만 다듬는다. 무엇을 요청할지는 백엔드가 이미 정했다.
# 다듬다가 재료 이름이 사라지면 위험하므로, 결과를 검사해 통과하지 못하면
# 원문을 그대로 쓴다 (app/card/polish.py).


@app.post("/card/polish", response_model=PolishResponse)
def polish_card(request: PolishRequest) -> PolishResponse:
    """주문 요청 문구를 매장에 건네기 자연스럽게 다듬는다.

    실패해도 오류를 내지 않는다. 다듬기는 있으면 좋은 것이지 반드시 필요한
    것이 아니며, 카드는 원문만으로도 제 역할을 한다.
    """
    return app.state.polisher.polish(request)
