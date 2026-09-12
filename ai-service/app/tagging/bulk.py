"""여러 음식을 한 번에 태깅한다.

**왜 동기 방식인가** — Google AI Studio 무료 티어에서는 Batch API를 쓸 수
없다(2026-09 확인). 따라서 건별 호출을 분당 요청 수(RPM) 한도에 맞춰 간격을
두고 보낸다.

**왜 한 번에 다 하지 않는가** — 무료 티어는 일일 요청 수(RPD)도 제한한다.
한 번에 전부 처리하려 들면 중간에 한도에 걸려 실패한다. 그래서 이 모듈은
"주어진 만큼만 처리하고 결과를 돌려주는" 역할만 하고, 남은 음식은 다음 실행이
가져간다. 재개 지점은 DB의 미태깅 큐(``dish.tagged_at IS NULL``)가 알아서
관리하므로 여기에 상태를 두지 않는다.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass

from app.config import settings
from app.tagging.llm import DishAnalyzer
from app.tagging.models import LlmDishAnalysis

log = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class DishRequest:
    """태깅할 음식 1건."""

    dish_id: str
    dish_name: str
    food_category: str | None = None


#: 재시도해도 소용없는 실패의 표지.
#:
#: 실측 사례: 지출 한도를 넘기면 429가 돌아오는데, 이건 분당 한도와 달리
#: 기다린다고 풀리지 않는다. 그런데도 남은 건을 계속 호출하면 30건짜리 실행이
#: 3분을 통째로 날린다.
_FATAL_MARKERS: tuple[str, ...] = (
    "spending cap",
    "spend cap",
    "quota exceeded",
    "billing",
    "api key not valid",
    "permission denied",
)


def is_fatal(message: str) -> bool:
    """실행 전체를 멈춰야 하는 오류인지.

    문자열 검사는 제공자 메시지가 바뀌면 놓칠 수 있다. 그래서 이것만 믿지 않고
    연속 실패 차단기(:class:`BulkTagger`)를 함께 둔다.
    """
    lowered = message.lower()
    return any(marker in lowered for marker in _FATAL_MARKERS)


@dataclass(frozen=True, slots=True)
class DishOutcome:
    """태깅 결과 1건. 실패도 결과다 — 조용히 사라지면 안 된다."""

    dish_id: str
    analysis: LlmDishAnalysis | None
    error: str | None = None
    #: 앞선 오류로 실행이 중단되어 아예 시도하지 못한 건.
    #: 실패와 구분해야 "왜 태그가 없는지" 를 설명할 수 있다.
    skipped: bool = False

    @property
    def succeeded(self) -> bool:
        return self.analysis is not None


class RateLimiter:
    """분당 요청 수를 지키도록 호출 간격을 벌린다.

    무료 티어의 RPM을 넘기면 429가 돌아오고, 재시도해도 같은 분 안에서는
    계속 막힌다. 애초에 넘기지 않는 편이 단순하고 빠르다.
    """

    def __init__(
        self,
        requests_per_minute: int,
        sleep=time.sleep,
        clock=time.monotonic,
    ) -> None:
        if requests_per_minute <= 0:
            raise ValueError("RPM은 1 이상이어야 합니다")
        self._interval = 60.0 / requests_per_minute
        self._sleep = sleep
        # 시계를 주입받는 이유: sleep 만 대역으로 바꾸면 잠들지 않은 채 실제
        # 시각만 흘러가 간격 계산이 어긋난다. 둘은 같은 시간축이어야 한다.
        self._clock = clock
        self._last: float | None = None

    def wait(self) -> None:
        current = self._clock()
        if self._last is not None:
            remaining = self._interval - (current - self._last)
            if remaining > 0:
                self._sleep(remaining)
                current = self._clock()
        self._last = current


class BulkTagger:
    """음식 목록을 순서대로 태깅한다."""

    #: 이만큼 연속으로 실패하면 남은 건을 시도하지 않는다.
    #: 제공자가 메시지를 바꿔 :func:`is_fatal` 이 놓치더라도 여기서 걸린다.
    MAX_CONSECUTIVE_FAILURES = 3

    def __init__(
        self,
        analyzer: DishAnalyzer | None = None,
        limiter: RateLimiter | None = None,
    ) -> None:
        self._analyzer = analyzer or DishAnalyzer()
        self._limiter = limiter or RateLimiter(settings.gemini_rpm)

    @property
    def model_id(self) -> str:
        return self._analyzer.model_id

    def tag(self, dishes: list[DishRequest]) -> list[DishOutcome]:
        """주어진 음식을 시도하고 건별 결과를 돌려준다.

        한 건이 실패해도 나머지는 계속한다 — 하나가 넘어졌다고 전체를 버리면
        그날 처리량을 통째로 잃는다.

        다만 **더 해봐야 소용없는 실패**(지출 한도 초과, 잘못된 키)이거나
        연속 실패가 이어지면 멈춘다. 시도하지 못한 건은 ``skipped`` 로 표시해
        실패와 구분한다. 그 음식들은 태깅되지 않은 채로 남아 다음 실행이
        다시 가져간다.
        """
        outcomes: list[DishOutcome] = []
        consecutive = 0
        stopped = False

        for dish in dishes:
            if stopped:
                outcomes.append(
                    DishOutcome(dish.dish_id, None, error="앞선 오류로 중단", skipped=True)
                )
                continue

            self._limiter.wait()
            outcome = self._tag_one(dish)
            outcomes.append(outcome)

            if outcome.succeeded:
                consecutive = 0
                continue

            consecutive += 1
            message = outcome.error or ""
            if is_fatal(message):
                log.error("복구 불가능한 오류로 태깅을 중단합니다: %s", message[:200])
                stopped = True
            elif consecutive >= self.MAX_CONSECUTIVE_FAILURES:
                log.error("연속 %d건 실패로 태깅을 중단합니다", consecutive)
                stopped = True

        return outcomes

    def _tag_one(self, dish: DishRequest) -> DishOutcome:
        try:
            analysis = self._analyzer.analyze(dish.dish_name, dish.food_category)
        except Exception as exc:  # noqa: BLE001 - 제공자 예외 종류가 다양하다
            # 태그 없는 음식이 '안전'으로 보이면 안 되므로 실패를 명시적으로 남긴다.
            log.warning("태깅 실패 %s(%s): %s", dish.dish_name, dish.dish_id, exc)
            return DishOutcome(dish.dish_id, None, error=f"{type(exc).__name__}: {exc}")
        return DishOutcome(dish.dish_id, analysis)
