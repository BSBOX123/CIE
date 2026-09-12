"""영양성분 수치 -> 주의성분 태그 (SPEC 7.3).

수치로 판정 가능한 5종은 여기서 결정론적으로 처리한다. LLM은 수치가 없거나
DB에 항목 자체가 없는 성분(정제 탄수화물, 퓨린)만 담당한다.

**두 가지 안전 요건**

1. 미측정(``None``)을 '안전'으로 읽지 않는다. 백미밥 같은 가공식품 레코드는
   칼륨이 비어 있는데, 이를 0으로 취급하면 만성콩팥병 사용자에게 '칼륨 걱정
   없음'이라고 잘못 안내하게 된다.
2. 판정은 **1회 섭취량 기준**으로 한다. API 수치는 100g로 정규화되어 있어
   그대로 비교하면 국물 음식이 과소평가된다. 자세한 근거는
   :mod:`app.tagging.portion` 참조.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Final

from app.nutrition.field_map import Nutrient
from app.tagging.portion import portion_for, scale_to_portion
from app.tagging.vocabulary import CareType


class Determination(StrEnum):
    """수치 기반 판정 결과."""

    FLAGGED = "FLAGGED"      # 임계값 초과 -> 주의성분으로 태깅
    CLEAR = "CLEAR"          # 임계값 미만 -> 태깅하지 않음
    UNKNOWN = "UNKNOWN"      # 미측정 -> LLM에 위임. 절대 CLEAR로 강등 금지


@dataclass(frozen=True, slots=True)
class Threshold:
    """1회 섭취량 기준 임계값.

    근거는 한국인 영양소 섭취기준의 1일 목표치를 한 끼(1/3)로 나눈 값이다.
    영양 전문가 검토 대상이며 설정으로 조정 가능해야 한다.
    """

    nutrient: Nutrient
    limit_per_serving: float
    unit: str
    rationale: str


#: 수치로 판정 가능한 주의성분 (1회 섭취량 기준)
THRESHOLDS: Final[dict[CareType, Threshold]] = {
    CareType.SODIUM: Threshold(
        Nutrient.SODIUM, 600.0, "mg", "1일 목표 2,000mg의 한 끼분(1/3)"
    ),
    CareType.SUGAR: Threshold(
        Nutrient.SUGAR, 12.0, "g", "WHO 자유당 권고 25g/일의 약 1/2"
    ),
    CareType.SATURATED_FAT: Threshold(
        Nutrient.SATURATED_FAT, 6.0, "g", "1일 15g 기준의 한 끼분"
    ),
    CareType.POTASSIUM: Threshold(
        Nutrient.POTASSIUM, 700.0, "mg", "만성콩팥병 제한 2,000mg/일의 한 끼분"
    ),
    CareType.PROTEIN_LOAD: Threshold(
        Nutrient.PROTEIN, 25.0, "g", "저단백 식이 기준의 한 끼분"
    ),
}

#: 영양성분DB로 판정할 수 없어 LLM이 담당하는 주의성분
LLM_ONLY: Final[frozenset[CareType]] = frozenset(
    {CareType.REFINED_CARB, CareType.PURINE}
)


@dataclass(frozen=True, slots=True)
class CareDetermination:
    care: CareType
    result: Determination
    per_100g: float | None
    per_serving: float | None
    limit: float | None
    unit: str | None
    portion_g: float

    @property
    def evidence(self) -> str:
        """사용자와 검수자 모두에게 보여줄 근거 문장."""
        if self.result is Determination.UNKNOWN:
            return f"{self.care} 판정 근거 수치 없음 (영양성분DB 미측정)"
        verb = "초과" if self.result is Determination.FLAGGED else "이하"
        return (
            f"{self.care} {self.per_serving:.0f}{self.unit}"
            f" (1회 {self.portion_g:.0f}g 기준, {self.per_100g}{self.unit}/100g)"
            f" — 기준 {self.limit:.0f}{self.unit} {verb}"
        )


def evaluate(
    nutrition: dict[Nutrient, float | None],
    food_category: str | None = None,
) -> list[CareDetermination]:
    """1회 섭취량 기준으로 주의성분을 평가한다.

    ``food_category`` 는 식약처 ``FOOD_CAT1_NM`` (예: "찌개 및 전골류").
    없으면 기본 섭취량을 적용한다.
    """
    grams = portion_for(food_category)
    out: list[CareDetermination] = []
    for care, threshold in THRESHOLDS.items():
        value = nutrition.get(threshold.nutrient)
        if value is None:
            out.append(
                CareDetermination(
                    care, Determination.UNKNOWN, None, None, None, None, grams
                )
            )
            continue
        per_serving = scale_to_portion(value, food_category)
        result = (
            Determination.FLAGGED
            if per_serving >= threshold.limit_per_serving
            else Determination.CLEAR
        )
        out.append(
            CareDetermination(
                care, result, value, per_serving,
                threshold.limit_per_serving, threshold.unit, grams,
            )
        )
    return out


def flagged_cares(
    nutrition: dict[Nutrient, float | None], food_category: str | None = None
) -> list[CareType]:
    """임계값을 넘은 주의성분만 추린다."""
    return [
        d.care for d in evaluate(nutrition, food_category)
        if d.result is Determination.FLAGGED
    ]


def unresolved_cares(
    nutrition: dict[Nutrient, float | None], food_category: str | None = None
) -> list[CareType]:
    """LLM 판단이 필요한 주의성분 (미측정 + 애초에 DB에 없는 항목)."""
    unknown = [
        d.care for d in evaluate(nutrition, food_category)
        if d.result is Determination.UNKNOWN
    ]
    return unknown + sorted(LLM_ONLY)
