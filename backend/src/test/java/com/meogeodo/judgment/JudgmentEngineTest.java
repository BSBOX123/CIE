package com.meogeodo.judgment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 판정 규칙 테스트 (SPEC 3장, 13.1).
 *
 * <p>이 서비스에서 가장 중요한 로직이다. 특히 메뉴는 비관적으로, 식당은
 * 낙관적으로 판정하는 반대 방향을 케이스로 고정한다.
 */
class JudgmentEngineTest {

  private final JudgmentEngine engine = new JudgmentEngine();

  /** 당뇨 + 고혈압, 새우·고등어 알레르기. 프론트 캔버스의 기본 프로필. */
  private static final UserHealthProfile 김영수 =
      new UserHealthProfile(
          Set.of("나트륨", "당류", "정제 탄수화물", "포화지방"), Set.of("새우", "고등어"));

  @Nested
  @DisplayName("메뉴 판정 — 비관적")
  class MenuJudgment {

    @Test
    @DisplayName("알레르겐이 하나라도 겹치면 RED")
    void allergenWins() {
      MenuTags 물회 = MenuTags.of(Set.of("나트륨", "당류"), Set.of("새우", "오징어"));
      assertThat(engine.judgeMenu(물회, 김영수)).isEqualTo(Verdict.RED);
    }

    @Test
    @DisplayName("알레르기는 주의성분보다 우선한다 — 조절로 해결되지 않으므로")
    void allergenOutranksCare() {
      MenuTags both = MenuTags.of(Set.of("나트륨"), Set.of("새우"));
      assertThat(engine.judgeMenu(both, 김영수)).isEqualTo(Verdict.RED);
    }

    @Test
    @DisplayName("주의성분만 겹치면 INK")
    void careOnly() {
      MenuTags 순두부백반 = MenuTags.of(Set.of("나트륨"), Set.of("대두"));
      assertThat(engine.judgeMenu(순두부백반, 김영수)).isEqualTo(Verdict.INK);
    }

    @Test
    @DisplayName("겹치는 것이 없으면 OK")
    void nothingHits() {
      MenuTags 가리비구이 = MenuTags.of(Set.of(), Set.of("조개류"));
      assertThat(engine.judgeMenu(가리비구이, 김영수)).isEqualTo(Verdict.OK);
    }

    @Test
    @DisplayName("내 알레르기가 아닌 알레르겐은 판정을 바꾸지 않는다")
    void otherPeoplesAllergens() {
      MenuTags 돼지고기메뉴 = MenuTags.of(Set.of(), Set.of("돼지고기", "대두"));
      assertThat(engine.judgeMenu(돼지고기메뉴, 김영수)).isEqualTo(Verdict.OK);
    }

    @Test
    @DisplayName("프로필이 비어 있으면 모두 OK")
    void emptyProfile() {
      MenuTags 물회 = MenuTags.of(Set.of("나트륨"), Set.of("새우"));
      assertThat(engine.judgeMenu(물회, UserHealthProfile.empty())).isEqualTo(Verdict.OK);
    }
  }

  @Nested
  @DisplayName("식당 판정 — 낙관적 (메뉴와 반대 방향)")
  class RestaurantJudgment {

    @Test
    @DisplayName("안전한 메뉴가 하나라도 있으면 OK")
    void oneSafeMenuIsEnough() {
      List<MenuTags> menus =
          List.of(
              MenuTags.of(Set.of(), Set.of("새우")), // RED
              MenuTags.of(Set.of("나트륨"), Set.of()), // INK
              MenuTags.of(Set.of(), Set.of("대두"))); // OK
      assertThat(engine.judgeRestaurant(menus, 김영수)).isEqualTo(Verdict.OK);
    }

    @Test
    @DisplayName("RED 메뉴가 섞여 있어도 OK 메뉴가 있으면 OK — 메뉴 판정과 반대")
    void oppositeOfMenuDirection() {
      MenuTags red = MenuTags.of(Set.of(), Set.of("새우"));
      MenuTags ok = MenuTags.of(Set.of(), Set.of());
      assertThat(engine.judgeMenu(red, 김영수)).isEqualTo(Verdict.RED);
      assertThat(engine.judgeRestaurant(List.of(red, ok), 김영수)).isEqualTo(Verdict.OK);
    }

    @Test
    @DisplayName("OK가 없고 INK가 있으면 INK")
    void inkFallback() {
      List<MenuTags> menus =
          List.of(MenuTags.of(Set.of(), Set.of("새우")), MenuTags.of(Set.of("나트륨"), Set.of()));
      assertThat(engine.judgeRestaurant(menus, 김영수)).isEqualTo(Verdict.INK);
    }

    @Test
    @DisplayName("전부 RED여야 RED")
    void allRed() {
      List<MenuTags> menus =
          List.of(
              MenuTags.of(Set.of(), Set.of("새우")), MenuTags.of(Set.of(), Set.of("고등어")));
      assertThat(engine.judgeRestaurant(menus, 김영수)).isEqualTo(Verdict.RED);
    }

    @Test
    @DisplayName("메뉴가 없으면 판정하지 않는다 — OK로 답하면 안전하다는 잘못된 신호")
    void noMenusMeansNoVerdict() {
      assertThat(engine.judgeRestaurant(List.of(), 김영수)).isNull();
      assertThat(engine.judgeRestaurant(null, 김영수)).isNull();
    }
  }

  @Nested
  @DisplayName("적중 항목 추출")
  class Hits {

    @Test
    @DisplayName("내 알레르기와 겹치는 것만 돌려준다")
    void onlyMine() {
      MenuTags 물회 = MenuTags.of(Set.of("나트륨", "칼륨"), Set.of("새우", "오징어"));
      assertThat(engine.hitMainAllergens(물회, 김영수)).containsExactly("새우");
      assertThat(engine.hitCares(물회, 김영수)).containsExactly("나트륨");
    }
  }

  @Nested
  @DisplayName("요약 문구 (SPEC 3.3)")
  class Summary {

    @Test
    void 알레르겐_하나() {
      MenuTags tags = MenuTags.of(Set.of(), Set.of("새우"));
      assertThat(engine.summarize(tags, 김영수)).isEqualTo("새우 있음");
    }

    @Test
    void 알레르겐_여럿() {
      MenuTags tags = MenuTags.of(Set.of(), Set.of("새우", "고등어"));
      assertThat(engine.summarize(tags, 김영수)).endsWith("외 1 있음");
    }

    @Test
    void 주의성분_하나() {
      MenuTags tags = MenuTags.of(Set.of("나트륨"), Set.of());
      assertThat(engine.summarize(tags, 김영수)).isEqualTo("나트륨 조절 필요");
    }

    @Test
    void 해당없음() {
      assertThat(engine.summarize(MenuTags.of(Set.of(), Set.of()), 김영수))
          .isEqualTo("조절 불필요");
    }

    @Test
    @DisplayName("알레르겐이 있으면 주의성분보다 먼저 알린다")
    void allergenFirst() {
      MenuTags tags = MenuTags.of(Set.of("나트륨"), Set.of("새우"));
      assertThat(engine.summarize(tags, 김영수)).isEqualTo("새우 있음");
    }
  }

  @Nested
  @DisplayName("낙관 표기")
  class Seals {

    @Test
    @DisplayName("색 외에 기호와 라벨이 항상 있다 (SPEC 5.2 접근성)")
    void symbolsAndLabels() {
      assertThat(Verdict.RED.symbol()).isEqualTo("✕");
      assertThat(Verdict.INK.symbol()).isEqualTo("△");
      assertThat(Verdict.OK.symbol()).isEqualTo("○");
      for (Verdict v : Verdict.values()) {
        assertThat(v.label()).isNotBlank();
      }
    }
  }

  @Nested
  @DisplayName("알레르겐 함유 정도 — 주재료와 양념 미량을 나눈다 (SPEC 3.1)")
  class AllergenAmount {

    @Test
    @DisplayName("주재료로 들어가면 RED — 빼달라고 할 수 없다")
    void mainIsRed() {
      MenuTags 물회 = new MenuTags(Set.of(), Set.of("새우"), Set.of());
      assertThat(engine.judgeMenu(물회, 김영수)).isEqualTo(Verdict.RED);
    }

    @Test
    @DisplayName("양념에 미량이면 INK — 빼달라고 요청할 여지가 있다")
    void traceIsInk() {
      MenuTags 국물요리 = new MenuTags(Set.of(), Set.of(), Set.of("새우"));
      assertThat(engine.judgeMenu(국물요리, 김영수)).isEqualTo(Verdict.INK);
    }

    @Test
    @DisplayName("주재료가 양념 미량보다 우선한다")
    void mainOutranksTrace() {
      MenuTags both = new MenuTags(Set.of(), Set.of("새우"), Set.of("고등어"));
      assertThat(engine.judgeMenu(both, 김영수)).isEqualTo(Verdict.RED);
    }

    @Test
    @DisplayName("양념 미량 알레르겐도 주의성분과 같은 INK지만 따로 조회된다")
    void traceIsQueryableSeparately() {
      MenuTags tags = new MenuTags(Set.of("나트륨"), Set.of(), Set.of("새우"));
      assertThat(engine.hitTraceAllergens(tags, 김영수)).containsExactly("새우");
      assertThat(engine.hitMainAllergens(tags, 김영수)).isEmpty();
      assertThat(engine.hitCares(tags, 김영수)).containsExactly("나트륨");
    }

    @Test
    @DisplayName("실측 문제 재현 — 간장의 밀이 모든 메뉴를 막지 않는다")
    void soySauceDoesNotBlockEverything() {
      // 강릉 실측: 29건 중 19건에 '밀'이 붙어 밀 알레르기 사용자가 쓸
      // 수 있는 메뉴가 34%였다. 양념 수준을 TRACE 로 내리면 ✕가 아니라 △가 된다.
      UserHealthProfile 밀알레르기 = new UserHealthProfile(Set.of(), Set.of("밀"));
      MenuTags 간장국물 = new MenuTags(Set.of(), Set.of(), Set.of("밀", "대두"));
      MenuTags 칼국수 = new MenuTags(Set.of(), Set.of("밀"), Set.of());

      assertThat(engine.judgeMenu(간장국물, 밀알레르기)).isEqualTo(Verdict.INK);
      assertThat(engine.judgeMenu(칼국수, 밀알레르기)).isEqualTo(Verdict.RED);
    }

    @Test
    @DisplayName("요약 문구가 양념 미량임을 드러낸다")
    void summaryDistinguishes() {
      MenuTags trace = new MenuTags(Set.of(), Set.of(), Set.of("새우"));
      assertThat(engine.summarize(trace, 김영수)).isEqualTo("새우 양념에 있음");

      MenuTags main = new MenuTags(Set.of(), Set.of("새우"), Set.of());
      assertThat(engine.summarize(main, 김영수)).isEqualTo("새우 있음");
    }

    @Test
    @DisplayName("식당 판정에서도 양념 미량은 갈 만한 곳으로 남는다")
    void restaurantWithOnlyTrace() {
      UserHealthProfile 밀알레르기 = new UserHealthProfile(Set.of(), Set.of("밀"));
      List<MenuTags> menus =
          List.of(
              new MenuTags(Set.of(), Set.of("밀"), Set.of()),   // 칼국수 -> RED
              new MenuTags(Set.of(), Set.of(), Set.of("밀")));  // 간장국 -> INK
      assertThat(engine.judgeRestaurant(menus, 밀알레르기)).isEqualTo(Verdict.INK);
    }
  }
}
