"""주문요청카드 문구 다듬기 테스트.

카드는 매장에 건네는 물건이고 알레르기를 알리는 수단이다. 다듬는 과정에서
재료 이름이 사라지면 사람이 다칠 수 있으므로, 안전 검사가 핵심이다.
"""

from __future__ import annotations

from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.card.models import PolishRequest
from app.card.polish import PhrasePolisher
from app.main import app


class StubClient:
    """지정한 문구를 돌려주는 대역."""

    def __init__(self, phrases=None, raise_error=False):
        self._phrases = phrases
        self._raise = raise_error
        self.interactions = SimpleNamespace(create=self._create)

    def _create(self, **kwargs):
        if self._raise:
            raise RuntimeError("429 rate limit")
        import json
        return SimpleNamespace(
            output_text=json.dumps({"phrases": self._phrases}, ensure_ascii=False))


def polisher(phrases=None, raise_error=False):
    return PhrasePolisher(client=StubClient(phrases, raise_error))


# ── 정상 다듬기 ─────────────────────────────────────────────────────

def test_문구를_다듬는다():
    result = polisher(["새우를 빼 주시겠어요?"]).polish(
        PolishRequest(phrases=["새우는 빼 주세요"], must_keep=["새우"]))

    assert result.phrases[0].polished == "새우를 빼 주시겠어요?"
    assert result.phrases[0].original == "새우는 빼 주세요"
    assert result.phrases[0].fell_back is False


def test_여러_문구를_순서대로_처리한다():
    result = polisher(["국물 따로 주세요", "소금 적게요"]).polish(
        PolishRequest(phrases=["국물은 따로 담아 주세요", "소금·간장은 반만 넣어 주세요"]))

    assert [p.polished for p in result.phrases] == ["국물 따로 주세요", "소금 적게요"]


# ── 안전 검사: 재료 이름이 사라지면 원문을 쓴다 ──────────────────────

def test_재료_이름이_사라지면_원문을_쓴다():
    """'새우'를 '해산물'로 바꾸면 알레르기 환자가 위험하다."""
    result = polisher(["해산물을 빼 주세요"]).polish(
        PolishRequest(phrases=["새우는 빼 주세요"], must_keep=["새우"]))

    assert result.phrases[0].polished == "새우는 빼 주세요"
    assert result.phrases[0].fell_back is True


def test_일부만_문제면_그것만_원문을_쓴다():
    result = polisher(["해산물 빼 주세요", "국물 따로 주세요"]).polish(
        PolishRequest(
            phrases=["새우는 빼 주세요", "국물은 따로 담아 주세요"],
            must_keep=["새우"]))

    assert result.phrases[0].fell_back is True
    assert result.phrases[0].polished == "새우는 빼 주세요"
    assert result.phrases[1].fell_back is False
    assert result.phrases[1].polished == "국물 따로 주세요"


def test_원문에_없던_낱말은_따지지_않는다():
    """must_keep 에 있어도 그 문구의 원문에 없었다면 검사 대상이 아니다."""
    result = polisher(["국물 따로 주세요"]).polish(
        PolishRequest(phrases=["국물은 따로 담아 주세요"], must_keep=["새우", "고등어"]))

    assert result.phrases[0].fell_back is False


# ── 개수·형식이 어긋나면 전부 원문 ──────────────────────────────────

def test_개수가_달라지면_전부_원문을_쓴다():
    """문구를 합치거나 나누면 어느 것이 어느 것인지 알 수 없다."""
    result = polisher(["둘을 합친 문구"]).polish(
        PolishRequest(phrases=["새우는 빼 주세요", "국물은 따로 담아 주세요"]))

    assert all(p.fell_back for p in result.phrases)
    assert [p.polished for p in result.phrases] == [
        "새우는 빼 주세요", "국물은 따로 담아 주세요"]


def test_빈_문구를_돌려주면_원문을_쓴다():
    result = polisher(["   "]).polish(PolishRequest(phrases=["새우는 빼 주세요"]))
    assert result.phrases[0].fell_back is True


def test_LLM이_실패해도_카드는_만들어진다():
    """다듬기는 있으면 좋은 것이지 반드시 필요한 것이 아니다."""
    result = polisher(raise_error=True).polish(
        PolishRequest(phrases=["새우는 빼 주세요"], must_keep=["새우"]))

    assert result.phrases[0].polished == "새우는 빼 주세요"
    assert result.phrases[0].fell_back is True
    assert result.model_id is None


def test_성공하면_모델_id를_남긴다():
    result = polisher(["새우 빼 주세요"]).polish(
        PolishRequest(phrases=["새우는 빼 주세요"], must_keep=["새우"]))
    assert result.model_id


# ── 엔드포인트 ──────────────────────────────────────────────────────

@pytest.fixture
def client():
    with TestClient(app) as c:
        c.app.state.polisher = polisher(["새우 빼 주세요"])
        yield c


def test_엔드포인트(client):
    resp = client.post("/card/polish", json={
        "phrases": ["새우는 빼 주세요"], "must_keep": ["새우"],
        "menu_label": "초당할머니순두부 · 순두부 백반"})

    assert resp.status_code == 200
    assert resp.json()["phrases"][0]["polished"] == "새우 빼 주세요"


def test_빈_목록은_거부한다(client):
    assert client.post("/card/polish", json={"phrases": []}).status_code == 422


def test_LLM_실패해도_200(client):
    client.app.state.polisher = polisher(raise_error=True)
    resp = client.post("/card/polish", json={"phrases": ["새우는 빼 주세요"]})
    assert resp.status_code == 200
    assert resp.json()["phrases"][0]["fell_back"] is True
