"""SPEC 2장 고정 어휘.

이 문자열들은 백엔드 enum, DB 코드값, 프론트 칩 라벨과 **1:1로 일치해야 한다.**
철자가 하나만 달라도 판정이 조용히 실패한다(교집합 연산이 빈 집합이 됨).
"""

from __future__ import annotations

from enum import StrEnum
from typing import Final


class CareType(StrEnum):
    """주의성분 7종 (SPEC 2.2)."""

    SODIUM = "나트륨"
    SUGAR = "당류"
    REFINED_CARB = "정제 탄수화물"
    SATURATED_FAT = "포화지방"
    POTASSIUM = "칼륨"
    PURINE = "퓨린"
    PROTEIN_LOAD = "단백질량"


class Allergen(StrEnum):
    """알레르기 유발물질 19종 (SPEC 2.4, 식약처 표시 대상 전체)."""

    EGG = "난류"
    MILK = "우유"
    BUCKWHEAT = "메밀"
    PEANUT = "땅콩"
    SOY = "대두"
    WHEAT = "밀"
    MACKEREL = "고등어"
    CRAB = "게"
    SHRIMP = "새우"
    PORK = "돼지고기"
    PEACH = "복숭아"
    TOMATO = "토마토"
    WALNUT = "호두"
    CHICKEN = "닭고기"
    BEEF = "쇠고기"
    SQUID = "오징어"
    SHELLFISH = "조개류"
    SULFITE = "아황산류"
    PINE_NUT = "잣"


class Disease(StrEnum):
    """지병 5종 (SPEC 2.1)."""

    DIABETES = "당뇨"
    HYPERTENSION = "고혈압"
    DYSLIPIDEMIA = "이상지질혈증"
    CKD = "만성콩팥병"
    GOUT = "통풍"


#: 질환 -> 자동 선택되는 주의성분 (SPEC 2.3)
DISEASE_MAP: Final[dict[Disease, tuple[CareType, ...]]] = {
    Disease.DIABETES: (CareType.SUGAR, CareType.REFINED_CARB),
    Disease.HYPERTENSION: (CareType.SODIUM, CareType.SATURATED_FAT),
    Disease.DYSLIPIDEMIA: (CareType.REFINED_CARB, CareType.SATURATED_FAT),
    Disease.CKD: (CareType.SODIUM, CareType.POTASSIUM, CareType.PROTEIN_LOAD),
    Disease.GOUT: (CareType.SODIUM, CareType.PURINE),
}


#: 주의성분 -> 사용자 안내 문구 (SPEC 2.2)
CARE_NOTES: Final[dict[CareType, str]] = {
    CareType.SODIUM: "국물∙양념∙젓갈에 몰려 있어요",
    CareType.SUGAR: "초고추장∙조림장에 설탕이 들어가요",
    CareType.REFINED_CARB: "흰밥∙면 양이 혈당을 좌우해요",
    CareType.SATURATED_FAT: "껍질∙비계∙진한 국물에 많아요",
    CareType.POTASSIUM: "채소∙해조류를 데치면 줄어요",
    CareType.PURINE: "진한 육수와 내장∙등푸른 생선에 많아요",
    CareType.PROTEIN_LOAD: "한 끼에 들어가는 고기∙생선∙두부 양으로 조절해요",
}
