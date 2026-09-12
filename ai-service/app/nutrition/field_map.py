"""식약처 식품영양성분DB(FoodNtrCpntDbInfo02) 응답 필드 매핑.

API는 영양소 이름 없이 ``AMT_NUM1`` ~ ``AMT_NUM110`` 형태의 라벨 없는 숫자만
돌려준다. 공식 활용가이드(xlsx)는 포털 로그인이 필요하므로, 조성이 알려진
음식들의 실제 API 응답값으로 매핑을 역산해 확정했다.

검증 근거는 ``tests/test_field_map.py`` 의 골든 테스트에 고정되어 있다.
가장 중요한 판별:

* ``AMT_NUM13`` 은 백미밥에서 2.0 mg 이다. 백미밥의 나트륨은 1~2 mg,
  칼륨은 25~30 mg 이므로 이 필드는 **나트륨일 수밖에 없다.**
* 배추김치에서 ``AMT_NUM12=334`` / ``AMT_NUM13=551`` 로, 실제값
  (칼륨 약 330 mg / 나트륨 약 550 mg)과 양쪽 모두 일치한다.

나트륨과 칼륨을 뒤바꾸면 만성콩팥병 사용자에게 정반대 조언이 나가므로,
이 모듈의 값은 골든 테스트 없이 수정해서는 안 된다.

SPEC.md 6.4(b) 참조.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Final


class Nutrient(StrEnum):
    """본 서비스가 사용하는 영양소."""

    ENERGY = "에너지"
    WATER = "수분"
    PROTEIN = "단백질"
    FAT = "지방"
    ASH = "회분"
    CARBOHYDRATE = "탄수화물"
    SUGAR = "당류"
    DIETARY_FIBER = "식이섬유"
    CALCIUM = "칼슘"
    IRON = "철"
    PHOSPHORUS = "인"
    POTASSIUM = "칼륨"
    SODIUM = "나트륨"
    CHOLESTEROL = "콜레스테롤"
    SATURATED_FAT = "포화지방산"
    TRANS_FAT = "트랜스지방산"


#: 영양소 -> API 응답 필드명. **골든 테스트 없이 수정 금지.**
FIELD_MAP: Final[dict[Nutrient, str]] = {
    Nutrient.ENERGY: "AMT_NUM1",
    Nutrient.WATER: "AMT_NUM2",
    Nutrient.PROTEIN: "AMT_NUM3",
    Nutrient.FAT: "AMT_NUM4",
    Nutrient.ASH: "AMT_NUM5",
    Nutrient.CARBOHYDRATE: "AMT_NUM6",
    Nutrient.SUGAR: "AMT_NUM7",
    Nutrient.DIETARY_FIBER: "AMT_NUM8",
    Nutrient.CALCIUM: "AMT_NUM9",
    Nutrient.IRON: "AMT_NUM10",
    Nutrient.PHOSPHORUS: "AMT_NUM11",
    Nutrient.POTASSIUM: "AMT_NUM12",
    Nutrient.SODIUM: "AMT_NUM13",
    Nutrient.CHOLESTEROL: "AMT_NUM23",
    Nutrient.SATURATED_FAT: "AMT_NUM24",
    Nutrient.TRANS_FAT: "AMT_NUM25",
}

#: 영양소 단위. 임계값(SPEC 7.3) 비교 시 단위 혼동을 막기 위해 명시한다.
UNITS: Final[dict[Nutrient, str]] = {
    Nutrient.ENERGY: "kcal",
    Nutrient.WATER: "g",
    Nutrient.PROTEIN: "g",
    Nutrient.FAT: "g",
    Nutrient.ASH: "g",
    Nutrient.CARBOHYDRATE: "g",
    Nutrient.SUGAR: "g",
    Nutrient.DIETARY_FIBER: "g",
    Nutrient.CALCIUM: "mg",
    Nutrient.IRON: "mg",
    Nutrient.PHOSPHORUS: "mg",
    Nutrient.POTASSIUM: "mg",
    Nutrient.SODIUM: "mg",
    Nutrient.CHOLESTEROL: "mg",
    Nutrient.SATURATED_FAT: "g",
    Nutrient.TRANS_FAT: "g",
}


def parse_amount(raw: str | None) -> float | None:
    """API의 문자열 수치를 float으로. 빈 값/결측은 ``None``.

    결측(``""``)과 실제 0을 구분하는 것이 중요하다. 측정되지 않은 성분을
    0으로 읽으면 "주의 성분 없음"으로 잘못 판정된다.
    """
    if raw is None:
        return None
    text = raw.strip()
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def extract(item: dict[str, str]) -> dict[Nutrient, float | None]:
    """API 응답 1건에서 영양소 값을 뽑아낸다 (100g 기준)."""
    return {
        nutrient: parse_amount(item.get(field))
        for nutrient, field in FIELD_MAP.items()
    }
