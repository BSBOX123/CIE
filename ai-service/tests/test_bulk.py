"""대량 태깅 테스트. Gemini 호출은 대역으로 바꾼다."""

from __future__ import annotations

import pytest

from app.tagging.bulk import BulkTagger, DishOutcome, DishRequest, RateLimiter
from app.tagging.models import AllergenAmount, AllergenFinding, LlmDishAnalysis
from app.tagging.vocabulary import Allergen


def analysis(**over) -> LlmDishAnalysis:
    base = dict(
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
    base.update(over)
    return LlmDishAnalysis(**base)


class StubAnalyzer:
    model_id = "gemini-3.8-flash"

    def __init__(self, fail_on: set[str] | None = None):
        self.fail_on = fail_on or set()
        self.seen: list[tuple[str, str | None]] = []

    def analyze(self, dish_name: str, food_category: str | None = None):
        self.seen.append((dish_name, food_category))
        if dish_name in self.fail_on:
            raise RuntimeError("429 rate limit")
        return analysis()


class FakeClock:
    """자지 않고 시간만 앞으로 돌린다. 시계와 sleep 이 같은 시간축을 쓴다."""

    def __init__(self):
        self.slept: list[float] = []
        self.now = 0.0

    def sleep(self, seconds: float) -> None:
        self.slept.append(seconds)
        self.now += seconds

    def time(self) -> float:
        return self.now


def tagger(analyzer=None, rpm=60, clock=None):
    clock = clock or FakeClock()
    return BulkTagger(
        analyzer=analyzer or StubAnalyzer(),
        limiter=RateLimiter(rpm, sleep=clock.sleep, clock=clock.time),
    )


# ── 정상 처리 ───────────────────────────────────────────────────────

def test_모든_음식을_태깅한다():
    stub = StubAnalyzer()
    outcomes = tagger(stub).tag([
        DishRequest("1", "김치찌개", "찌개 및 전골류"),
        DishRequest("2", "물회"),
    ])

    assert [o.dish_id for o in outcomes] == ["1", "2"]
    assert all(o.succeeded for o in outcomes)
    assert stub.seen == [("김치찌개", "찌개 및 전골류"), ("물회", None)]


def test_결과는_요청_순서를_지킨다():
    outcomes = tagger().tag([DishRequest(str(i), f"음식{i}") for i in range(5)])
    assert [o.dish_id for o in outcomes] == ["0", "1", "2", "3", "4"]


def test_빈_목록은_빈_결과():
    assert tagger().tag([]) == []


# ── 실패 처리 ───────────────────────────────────────────────────────

def test_한_건이_실패해도_나머지는_계속한다():
    """하나 넘어졌다고 전체를 버리면 그날 처리량을 통째로 잃는다."""
    stub = StubAnalyzer(fail_on={"물회"})
    outcomes = tagger(stub).tag([
        DishRequest("1", "김치찌개"),
        DishRequest("2", "물회"),
        DishRequest("3", "된장찌개"),
    ])

    by_id = {o.dish_id: o for o in outcomes}
    assert by_id["1"].succeeded
    assert by_id["3"].succeeded
    assert not by_id["2"].succeeded


def test_실패는_이유와_함께_남는다():
    """조용히 사라지면 태그 없는 음식이 '안전'으로 보인다."""
    outcomes = tagger(StubAnalyzer(fail_on={"물회"})).tag([DishRequest("2", "물회")])

    assert outcomes[0].analysis is None
    assert "429" in outcomes[0].error


def test_실패한_건도_결과_목록에_들어간다():
    outcomes = tagger(StubAnalyzer(fail_on={"a", "b"})).tag(
        [DishRequest("1", "a"), DishRequest("2", "b")]
    )
    assert len(outcomes) == 2


# ── 요청 속도 제한 ──────────────────────────────────────────────────

def test_RPM에_맞춰_간격을_둔다():
    """무료 티어에서 RPM을 넘기면 429가 나고 같은 분 안에서는 계속 막힌다."""
    clock = FakeClock()
    tagger(rpm=60, clock=clock).tag([DishRequest(str(i), f"음식{i}") for i in range(3)])

    # 60 RPM = 1초 간격. 첫 호출은 대기 없음.
    assert clock.slept == [1.0, 1.0]


def test_낮은_RPM일수록_간격이_길다():
    slow, fast = FakeClock(), FakeClock()
    tagger(rpm=6, clock=slow).tag([DishRequest("1", "a"), DishRequest("2", "b")])
    tagger(rpm=60, clock=fast).tag([DishRequest("1", "a"), DishRequest("2", "b")])

    assert slow.slept[0] > fast.slept[0]


def test_첫_호출은_기다리지_않는다():
    clock = FakeClock()
    tagger(rpm=6, clock=clock).tag([DishRequest("1", "a")])
    assert clock.slept == []


def test_RPM이_0이하면_거부한다():
    with pytest.raises(ValueError, match="1 이상"):
        RateLimiter(0)


def test_충분히_시간이_지났으면_기다리지_않는다():
    clock = FakeClock()
    limiter = RateLimiter(60, sleep=clock.sleep, clock=clock.time)  # 1초 간격
    limiter.wait()
    clock.now += 5.0  # 호출 자체가 오래 걸린 경우
    limiter.wait()
    assert clock.slept == []


def test_모델_id를_노출한다():
    assert tagger().model_id == "gemini-3.8-flash"


# ── 조기 중단 ───────────────────────────────────────────────────────
# 실측: 지출 한도를 넘기면 429가 오는데 기다려도 풀리지 않는다. 그런데도
# 남은 건을 계속 호출하면 30건짜리 실행이 3분을 통째로 날린다.

class FatalAnalyzer:
    model_id = "gemini-3.5-flash-lite"

    def __init__(self, message: str):
        self.message = message
        self.calls = 0

    def analyze(self, dish_name: str, food_category: str | None = None):
        self.calls += 1
        raise RuntimeError(self.message)


def test_지출한도_초과면_즉시_중단한다():
    stub = FatalAnalyzer("Error code: 429 - Your project has exceeded its monthly spending cap.")
    outcomes = tagger(stub).tag([DishRequest(str(i), f"음식{i}") for i in range(10)])

    assert stub.calls == 1, "한 번 확인하고 멈춰야 한다"
    assert len(outcomes) == 10, "결과는 요청 수만큼 돌려준다"
    assert outcomes[0].skipped is False
    assert all(o.skipped for o in outcomes[1:])


def test_중단된_건은_실패와_구분된다():
    """왜 태그가 없는지 설명하려면 '시도했다 실패'와 '시도조차 못함'이 달라야 한다."""
    stub = FatalAnalyzer("api key not valid")
    outcomes = tagger(stub).tag([DishRequest("1", "a"), DishRequest("2", "b")])

    assert outcomes[0].skipped is False and not outcomes[0].succeeded
    assert outcomes[1].skipped is True


def test_연속_실패가_이어지면_중단한다():
    """제공자가 메시지를 바꿔 문자열 검사가 놓쳐도 여기서 걸린다."""
    stub = FatalAnalyzer("알 수 없는 오류")
    outcomes = tagger(stub).tag([DishRequest(str(i), f"음식{i}") for i in range(10)])

    assert stub.calls == BulkTagger.MAX_CONSECUTIVE_FAILURES
    assert sum(o.skipped for o in outcomes) == 10 - stub.calls


def test_간헐적_실패는_중단시키지_않는다():
    """한 건 실패했다고 멈추면 그날 처리량을 잃는다."""
    stub = StubAnalyzer(fail_on={"음식1", "음식5"})
    outcomes = tagger(stub).tag([DishRequest(str(i), f"음식{i}") for i in range(8)])

    assert sum(o.succeeded for o in outcomes) == 6
    assert not any(o.skipped for o in outcomes)


def test_성공하면_연속_실패_카운트가_초기화된다():
    stub = StubAnalyzer(fail_on={"음식0", "음식1", "음식3", "음식4"})
    outcomes = tagger(stub).tag([DishRequest(str(i), f"음식{i}") for i in range(6)])

    # 실패 2 -> 성공(초기화) -> 실패 2 -> 성공. 3연속이 없으므로 끝까지 돈다.
    assert not any(o.skipped for o in outcomes)


def test_is_fatal_판별():
    from app.tagging.bulk import is_fatal
    assert is_fatal("Your project has exceeded its monthly spending cap.")
    assert is_fatal("API key not valid")
    assert is_fatal("Quota exceeded for quota metric")
    assert not is_fatal("429 rate limit exceeded, retry after 60s")
    assert not is_fatal("연결 시간 초과")
