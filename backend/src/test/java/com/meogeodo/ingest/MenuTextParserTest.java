package com.meogeodo.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 메뉴 파싱 테스트.
 *
 * <p>여기 쓰인 문자열은 전부 KorService2 {@code detailIntro2} 의 <b>실제 응답</b>
 * 에서 가져온 것이다 (2026-09-07 수집). 상상한 입력이 아니라 실제로 들어오는
 * 형태를 고정한다.
 */
class MenuTextParserTest {

  private final MenuTextParser parser = new MenuTextParser();

  @Nested
  @DisplayName("구분자 — 실제 데이터는 쉼표와 슬래시를 섞어 쓴다")
  class Separators {

    @Test
    @DisplayName("쉼표 구분")
    void comma() {
      assertThat(parser.parse("메밀 전병", "메밀 부치기, 올챙이국수 등"))
          .containsExactly("메밀 전병", "메밀 부치기", "올챙이국수");
    }

    @Test
    @DisplayName("슬래시 구분 — 공백 없음")
    void slashNoSpace() {
      assertThat(parser.parse("감자적", "감자옹심이/감자적/도토리들깨수제비"))
          .containsExactly("감자적", "감자옹심이", "도토리들깨수제비");
    }

    @Test
    @DisplayName("슬래시 뒤에만 공백")
    void slashTrailingSpace() {
      // raw_name 은 식당이 적은 원문 그대로 둔다. 오기 교정은 normalize 에서만
      // 하므로 여기서는 '누릉지'가 그대로 나온다.
      assertThat(parser.parse("풍천장어", "소면/ 누릉지 등"))
          .containsExactly("풍천장어", "소면", "누릉지");
    }

    @Test
    @DisplayName("슬래시 양쪽 공백")
    void slashBothSpaces() {
      assertThat(parser.parse("가방속메밀커피", "들깨꼬소커피 / 메밀싹라떼 / 꽃청에이드 등"))
          .containsExactly("가방속메밀커피", "들깨꼬소커피", "메밀싹라떼", "꽃청에이드");
    }
  }

  @Nested
  @DisplayName("말미 표현 제거")
  class TrailingNoise {

    @Test
    void 등_제거() {
      assertThat(parser.parse(null, "생선구이 등")).containsExactly("생선구이");
    }

    @Test
    @DisplayName("'등'으로 끝나지 않는 메뉴 이름은 건드리지 않는다")
    void 등으로_끝나는_이름은_보존() {
      // '갈치조림'처럼 마지막 글자가 '등'이 아닌 일반 케이스
      assertThat(parser.parse(null, "고등어조림")).containsExactly("고등어조림");
    }
  }

  @Nested
  @DisplayName("괄호 — 이름의 일부이므로 보존한다")
  class Parentheses {

    @Test
    @DisplayName("괄호를 버리면 무슨 음식인지 알 수 없게 된다")
    void keepParentheses() {
      assertThat(parser.parse("갈매기스페셜(모둠회+대게)중", null))
          .containsExactly("갈매기스페셜(모둠회+대게)중");
    }
  }

  @Nested
  @DisplayName("정규화 — 같은 음식을 하나의 dish로 모은다")
  class Normalization {

    @Test
    @DisplayName("크기만 다른 메뉴는 같은 dish가 된다")
    void sizesCollapse() {
      String 중 = parser.normalize("갈매기스페셜(모둠회+대게)중");
      String 대 = parser.normalize("갈매기스페셜(모둠회+대게)대");
      String 특대 = parser.normalize("갈매기스페셜(모둠회+대게)특대");
      assertThat(중).isEqualTo(대).isEqualTo(특대);
    }

    @Test
    @DisplayName("공백으로 떨어진 크기 표기도 처리한다")
    void spacedSize() {
      assertThat(parser.normalize("계절메뉴스페셜 대"))
          .isEqualTo(parser.normalize("계절메뉴스페셜 특대"));
    }

    @Test
    @DisplayName("이름 끝 글자를 크기로 오인하지 않는다")
    void doesNotEatRealNames() {
      // '전복죽', '모듬회', '수육'처럼 크기 글자와 무관한 이름
      assertThat(parser.normalize("전복죽")).isEqualTo("전복죽");
      assertThat(parser.normalize("모듬회")).isEqualTo("모듬회");
      assertThat(parser.normalize("흑염소수육")).isEqualTo("흑염소수육");
    }

    @Test
    @DisplayName("'대'로 끝나는 실제 음식명을 잘라내지 않는다")
    void wordEndingInSizeChar() {
      // 앞이 공백도 ')'도 아니면 크기로 보지 않는다
      assertThat(parser.normalize("순대")).isEqualTo("순대");
    }

    @Test
    @DisplayName("공백 차이를 흡수한다")
    void whitespaceInsensitive() {
      assertThat(parser.normalize("명품 한우 스페셜")).isEqualTo(parser.normalize("명품한우스페셜"));
    }

    @Test
    @DisplayName("흔한 오기를 교정해 dish가 갈라지지 않게 한다")
    void spellingFixes() {
      assertThat(parser.normalize("김치찌게")).isEqualTo(parser.normalize("김치찌개"));
      assertThat(parser.normalize("누릉지")).isEqualTo("누룽지");
      assertThat(parser.normalize("돈까스")).isEqualTo("돈가스");
    }
  }

  @Nested
  @DisplayName("결합 메뉴 분해")
  class Combos {

    @Test
    @DisplayName("최상위 +는 구성 음식으로 나눈다")
    void splitTopLevel() {
      assertThat(parser.splitCombo("꼬막비빔밥+물회")).containsExactly("꼬막비빔밥", "물회");
    }

    @Test
    @DisplayName("괄호 안의 +는 재료 설명이므로 나누지 않는다")
    void keepInsideParens() {
      assertThat(parser.splitCombo("갈매기스페셜(모둠회+대게)중"))
          .containsExactly("갈매기스페셜(모둠회+대게)중");
    }
  }

  @Nested
  @DisplayName("잡음 제거")
  class Junk {

    @Test
    void 빈값과_null() {
      assertThat(parser.parse(null, null)).isEmpty();
      assertThat(parser.parse("", "  ")).isEmpty();
    }

    @Test
    @DisplayName("firstmenu가 비어도 treatmenu만으로 동작한다")
    void onlyTreatMenu() {
      assertThat(parser.parse("", "불짬뽕빵")).containsExactly("불짬뽕빵");
    }

    @Test
    @DisplayName("중복은 등장 순서를 지키며 한 번만 남는다")
    void dedupe() {
      // 실제 사례: firstmenu가 treatmenu에도 다시 등장한다
      assertThat(parser.parse("흑염소탕", "흑염소탕 / 흑염소전골 / 흑염소수육"))
          .containsExactly("흑염소탕", "흑염소전골", "흑염소수육");
    }

    @Test
    void 가격_표기_제거() {
      assertThat(parser.parse(null, "순두부 백반 11,000원")).containsExactly("순두부 백반");
    }

    @Test
    @DisplayName("글자가 없는 조각은 버린다")
    void dropNonWords() {
      assertThat(parser.parse(null, "물회 / / 123 / 회덮밥"))
          .containsExactly("물회", "회덮밥");
    }
  }

  @Nested
  @DisplayName("실제 응답 전체 처리")
  class RealSamples {

    @Test
    void 강릉_옹심이집() {
      assertThat(parser.parse("감자전", "장칼옹심이, 장칼국수, 순옹심이 등"))
          .containsExactly("감자전", "장칼옹심이", "장칼국수", "순옹심이");
    }

    @Test
    void 회_전문점() {
      assertThat(
              parser.parse(
                  "갈매기스페셜(모둠회+대게)중",
                  "갈매기스페셜(모둠회+대게)대, 갈매기스페셜(모둠회+대게)특대, 계절메뉴스페셜 대,"
                      + " 계절메뉴스페셜 특대, 모듬회, 매운탕, 물회잡어, 물회, 회덮밥, 전복죽"))
          .contains("모듬회", "매운탕", "물회", "회덮밥", "전복죽")
          .hasSize(11); // firstmenu 1 + treatmenu 10
    }

    @Test
    @DisplayName("회 전문점의 11개 메뉴가 8개 dish로 모인다 — 태깅 비용이 줄어든다")
    void collapsesToFewerDishes() {
      var menus =
          parser.parse(
              "갈매기스페셜(모둠회+대게)중",
              "갈매기스페셜(모둠회+대게)대, 갈매기스페셜(모둠회+대게)특대, 계절메뉴스페셜 대,"
                  + " 계절메뉴스페셜 특대, 모듬회, 매운탕, 물회잡어, 물회, 회덮밥, 전복죽");
      var dishes = menus.stream().map(parser::normalize).distinct().toList();
      assertThat(menus).hasSize(11);
      // 갈매기스페셜 중/대/특대 -> 1, 계절메뉴스페셜 대/특대 -> 1, 나머지 6개
      assertThat(dishes).hasSize(8);
    }
  }
}
