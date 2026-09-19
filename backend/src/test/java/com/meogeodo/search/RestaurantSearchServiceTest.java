package com.meogeodo.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meogeodo.domain.Dish;
import com.meogeodo.domain.DishRepository;
import com.meogeodo.domain.DishTag;
import com.meogeodo.domain.DishTagRepository;
import com.meogeodo.domain.RestaurantFlag;
import com.meogeodo.domain.RestaurantFlagRepository;
import com.meogeodo.tour.FakeTourApi;
import com.meogeodo.tour.MenuTextParser;
import com.meogeodo.tour.TourApiException;
import com.meogeodo.user.UserService;
import com.meogeodo.web.AuthDtos.SignupRequest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * GPS 반경 검색 테스트 (MVP 2번).
 *
 * <p>식당·메뉴는 관광공사에서 실시간으로 받으므로 {@link FakeTourApi} 에 넣는다.
 * 음식 분석(dish·dish_tag)만 DB 에 있다. 좌표는 강릉 실제 데이터에서 가져왔다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RestaurantSearchServiceTest {

  // 경포대 기준점
  private static final double LAT = 37.7952, LNG = 128.8964;

  @Autowired private RestaurantSearchService search;
  @Autowired private FakeTourApi tour;
  @Autowired private MenuTextParser parser;
  @Autowired private DishRepository dishes;
  @Autowired private DishTagRepository dishTags;
  @Autowired private RestaurantFlagRepository flags;
  @Autowired private UserService userService;
  @Autowired private EntityManager em;

  private String near;
  private String far;

  @BeforeEach
  void setUp() {
    tour.reset();
    near = tour.add("가까운집", 37.7960, 128.8970, "강원특별자치도 강릉시 경포동 1");  // 약 100m
    far = tour.add("먼집", 37.8500, 128.9500, "강원특별자치도 강릉시 사천면 2");       // 약 8km
  }

  private long id(String contentId) {
    return Long.parseLong(contentId);
  }

  /** 음식 사전에 분석 결과를 넣는다. 메뉴 원문은 따로 {@code tour.menu} 로 넣는다. */
  private void analyzed(String menuName, Set<String> cares,
      Set<String> mainAllergens, Set<String> traceAllergens) {
    String normalized = parser.normalize(menuName);
    Dish dish = dishes.findByNormalizedName(normalized)
        .orElseGet(() -> dishes.save(new Dish(normalized)));
    dish.setTaggedAt(OffsetDateTime.now());
    dishes.save(dish);

    cares.forEach(c -> dishTags.save(
        new DishTag(dish, DishTag.TagType.CARE, c, DishTag.Source.NUTRITION_DB, BigDecimal.ONE)));
    mainAllergens.forEach(a -> {
      DishTag t = new DishTag(dish, DishTag.TagType.ALLERGEN, a, DishTag.Source.LLM,
          new BigDecimal("0.9"));
      t.setAmount(DishTag.Amount.MAIN);
      dishTags.save(t);
    });
    traceAllergens.forEach(a -> {
      DishTag t = new DishTag(dish, DishTag.TagType.ALLERGEN, a, DishTag.Source.LLM,
          new BigDecimal("0.9"));
      t.setAmount(DishTag.Amount.TRACE);
      dishTags.save(t);
    });
    em.flush();
  }

  /** 분석된 메뉴 하나를 가진 식당. */
  private void menu(String contentId, String menuName, Set<String> cares,
      Set<String> mainAllergens, Set<String> traceAllergens) {
    analyzed(menuName, cares, mainAllergens, traceAllergens);
    tour.menu(contentId, menuName, null);
  }

  private Long signup(String loginId, Set<String> diseases, Set<String> allergies) {
    return userService.signup(new SignupRequest(
        loginId, "secret123", "김영수", "남성", 1958, "A형",
        diseases, Set.of(), allergies, false, Set.of(), null, false)).getId();
  }

  @Nested
  @DisplayName("반경 검색")
  class Radius {

    @Test
    @DisplayName("식당 id 는 관광공사 contentid 다 — 저장하지 않으므로 다시 찾을 열쇠가 이것뿐이다")
    void idIsContentId() {
      var result = search.search(null, LAT, LNG, 2_000, null, null, 0, 10);
      assertThat(result.items()).extracting(SearchDtos.RestaurantSummary::id)
          .containsExactly(id(near));
    }

    @Test
    @DisplayName("거리와 도보 시간, 줄인 주소를 함께 준다")
    void distanceAndWalk() {
      var item = search.search(null, LAT, LNG, 2_000, null, null, 0, 10).items().get(0);
      assertThat(item.distanceM()).isBetween(1, 300);
      assertThat(item.walkMinutes()).isGreaterThanOrEqualTo(1);
      assertThat(item.meta()).isEqualTo("강릉시 경포동 · 도보 " + item.walkMinutes() + "분");
    }

    @Test
    @DisplayName("이름으로 거를 수 있다")
    void filterByQuery() {
      var result = search.search(null, LAT, LNG, 20_000, "먼", null, 0, 10);
      assertThat(result.items()).extracting(SearchDtos.RestaurantSummary::name)
          .containsExactly("먼집");
      assertThat(result.totalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("제보로 도출된 속성으로 거를 수 있다")
    void filterByFlag() {
      flags.save(new RestaurantFlag(id(far), "덜짜게 해줌", RestaurantFlag.Source.COMMUNITY));
      em.flush();

      var result = search.search(null, LAT, LNG, 20_000, null, List.of("덜짜게 해줌"), 0, 10);

      assertThat(result.items()).extracting(SearchDtos.RestaurantSummary::name)
          .containsExactly("먼집");
      assertThat(result.items().get(0).flags()).containsExactly("덜짜게 해줌");
    }

    @Test
    @DisplayName("페이지 기본 크기는 3 (프론트 PER_PAGE)")
    void defaultPageSize() {
      for (int i = 0; i < 5; i++) {
        tour.add("집" + i, 37.7955 + i * 0.0001, 128.8965, "강원특별자치도 강릉시 경포동");
      }

      var result = search.search(null, LAT, LNG, 2_000, null, null, 0, 0);
      assertThat(result.size()).isEqualTo(3);
      assertThat(result.items()).hasSize(3);
      assertThat(result.totalCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("다음 페이지는 이어서 나온다")
    void secondPage() {
      for (int i = 0; i < 5; i++) {
        tour.add("집" + i, 37.7955 + i * 0.0001, 128.8965, "강원특별자치도 강릉시 경포동");
      }
      var first = search.search(null, LAT, LNG, 2_000, null, null, 0, 3).items();
      var second = search.search(null, LAT, LNG, 2_000, null, null, 1, 3).items();

      assertThat(second).hasSize(3);
      assertThat(second).extracting(SearchDtos.RestaurantSummary::id)
          .doesNotContainAnyElementsOf(
              first.stream().map(SearchDtos.RestaurantSummary::id).toList());
    }
  }

  @Nested
  @DisplayName("관광공사 실패 — 빈 결과로 바꾸지 않는다")
  class Failure {

    @Test
    @DisplayName("API 가 죽으면 예외로 알린다 — '근처에 식당이 없다'로 답하지 않는다")
    void apiDownIsAnError() {
      tour.down(true);
      assertThatThrownBy(() -> search.search(null, LAT, LNG, 2_000, null, null, 0, 10))
          .isInstanceOf(TourApiException.class);
    }

    @Test
    @DisplayName("한 식당의 메뉴만 못 불러오면 그 식당만 '불러오지 못함'으로 표시한다")
    void introFailureIsNotNoMenu() {
      // 적재 시절의 사고: 한도 초과 응답이 '메뉴 없음'으로 저장돼 식당 86% 가 비었다.
      menu(near, "물회", Set.of(), Set.of("새우"), Set.of());
      tour.failIntro(near);
      Long userId = signup("fail1", Set.of(), Set.of("새우"));

      var item = search.search(userId, LAT, LNG, 2_000, null, null, 0, 10).items().get(0);

      assertThat(item.seal()).isNull();
      assertThat(item.summary()).isEqualTo(RestaurantSearchService.INTRO_FAILED);
    }

    @Test
    @DisplayName("메뉴가 정말 없는 식당은 '메뉴 정보가 없습니다'")
    void noMenu() {
      var item = search.search(null, LAT, LNG, 2_000, null, null, 0, 10).items().get(0);
      assertThat(item.summary()).isEqualTo(RestaurantSearchService.NO_MENU);
    }

    @Test
    @DisplayName("상세에서 메뉴를 못 불러오면 빈 메뉴가 아니라 예외다")
    void detailIntroFailure() {
      tour.failIntro(near);
      assertThatThrownBy(() -> search.detail(null, id(near), null, null))
          .isInstanceOf(TourApiException.class);
    }

    @Test
    @DisplayName("없는 식당은 404")
    void unknownRestaurant() {
      assertThatThrownBy(() -> search.detail(null, 999L, null, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("찾을 수 없습니다");
    }
  }

  @Nested
  @DisplayName("판정")
  class Judgment {

    @Test
    @DisplayName("비로그인은 판정 없이 목록만 — 추측해서 내보내지 않는다")
    void anonymousHasNoVerdict() {
      menu(near, "물회", Set.of("나트륨"), Set.of("새우"), Set.of());

      var result = search.search(null, LAT, LNG, 2_000, null, null, 0, 10);

      assertThat(result.personalized()).isFalse();
      assertThat(result.items().get(0).seal()).isNull();
      assertThat(result.items().get(0).summary()).isNull();
    }

    @Test
    @DisplayName("로그인하면 사용자 기준 판정이 붙는다")
    void personalizedVerdict() {
      menu(near, "물회", Set.of("나트륨"), Set.of("새우"), Set.of());
      Long userId = signup("u1", Set.of("고혈압"), Set.of("새우"));

      var result = search.search(userId, LAT, LNG, 2_000, null, null, 0, 10);

      assertThat(result.personalized()).isTrue();
      assertThat(result.items().get(0).seal().verdict()).isEqualTo("RED");
      assertThat(result.items().get(0).seal().symbol()).isEqualTo("✕");
    }

    @Test
    @DisplayName("알레르기가 없는 사용자에게는 같은 메뉴가 다르게 보인다")
    void differentUsersDifferentVerdict() {
      menu(near, "물회", Set.of(), Set.of("새우"), Set.of());
      Long shrimp = signup("u2", Set.of(), Set.of("새우"));
      Long none = signup("u3", Set.of(), Set.of("땅콩"));

      assertThat(search.search(shrimp, LAT, LNG, 2_000, null, null, 0, 10)
          .items().get(0).seal().verdict()).isEqualTo("RED");
      assertThat(search.search(none, LAT, LNG, 2_000, null, null, 0, 10)
          .items().get(0).seal().verdict()).isEqualTo("OK");
    }

    @Test
    @DisplayName("같은 음식은 어느 식당에서나 같은 판정 — 이름을 정규화해 사전을 찾는다")
    void normalizedLookup() {
      analyzed("김치찌개", Set.of("나트륨"), Set.of(), Set.of());
      tour.menu(near, "김치 찌게 등", null);  // 띄어쓰기·오기·'등'
      Long userId = signup("u8", Set.of("고혈압"), Set.of());

      var m = search.detail(userId, id(near), null, null).menus().get(0);

      assertThat(m.name()).isEqualTo("김치 찌게");
      assertThat(m.tagStatus()).isEqualTo("TAGGED");
      assertThat(m.hitCares()).containsExactly("나트륨");
    }
  }

  @Nested
  @DisplayName("상세")
  class Detail {

    @Test
    @DisplayName("메뉴별 판정과 요청 문구를 준다")
    void menuVerdicts() {
      menu(near, "순두부백반", Set.of("나트륨"), Set.of(), Set.of("대두"));
      Long userId = signup("u4", Set.of("고혈압"), Set.of("대두"));

      var detail = search.detail(userId, id(near), LAT, LNG);
      var m = detail.menus().get(0);

      // 대두가 양념에 있으므로 RED 가 아니라 INK
      assertThat(m.seal().verdict()).isEqualTo("INK");
      assertThat(m.hitTraceAllergens()).containsExactly("대두");
      assertThat(m.hitCares()).containsExactly("나트륨");
      assertThat(m.detail()).contains("양념").contains("매장에 확인");
      assertThat(m.suggestedRequests()).contains("대두는 빼 주세요");
    }

    @Test
    @DisplayName("주재료 알레르겐은 다른 메뉴를 권한다")
    void mainAllergenTellsToAvoid() {
      menu(near, "물회", Set.of(), Set.of("새우"), Set.of());
      Long userId = signup("u5", Set.of(), Set.of("새우"));

      var m = search.detail(userId, id(near), null, null).menus().get(0);

      assertThat(m.seal().verdict()).isEqualTo("RED");
      assertThat(m.detail()).contains("주재료").contains("다른 메뉴");
    }

    @Test
    @DisplayName("관광공사 소개 정보(전화·영업시간)를 함께 준다")
    void introFields() {
      tour.menu(near, "물회", null);
      var detail = search.detail(null, id(near), null, null);

      assertThat(detail.name()).isEqualTo("가까운집");
      assertThat(detail.tel()).isEqualTo("033-000-0000");
      assertThat(detail.openTime()).isEqualTo("10:00~21:00");
    }

    @Test
    @DisplayName("메뉴 id 는 이름에서 만든 값이라 다시 불러도 같다")
    void stableMenuId() {
      tour.menu(near, "물회, 회덮밥", null);

      var first = search.detail(null, id(near), null, null).menus();
      var again = search.detail(null, id(near), null, null).menus();

      assertThat(first).extracting(SearchDtos.MenuView::id)
          .containsExactlyElementsOf(again.stream().map(SearchDtos.MenuView::id).toList())
          .doesNotHaveDuplicates();
      assertThat(first.get(0).representative()).isTrue();
      assertThat(first.get(1).representative()).isFalse();
    }

    @Test
    @DisplayName("좌표를 안 주면 거리는 비어 있다")
    void detailWithoutCoordinates() {
      var detail = search.detail(null, id(near), null, null);
      assertThat(detail.distanceM()).isNull();
      assertThat(detail.walkMinutes()).isNull();
    }

    @Test
    @DisplayName("주의성분 안내 문구가 함께 온다")
    void careNote() {
      menu(near, "김치찌개", Set.of("나트륨"), Set.of(), Set.of());
      Long userId = signup("u7", Set.of("고혈압"), Set.of());

      var m = search.detail(userId, id(near), null, null).menus().get(0);
      assertThat(m.detail()).contains("국물");
    }

    @Test
    @DisplayName("면책 문구가 붙는다 (SPEC 11.1)")
    void disclaimer() {
      assertThat(search.detail(null, id(near), null, null).disclaimer())
          .contains("의학적 조언이 아닙니다");
    }
  }

  @Nested
  @DisplayName("조사 처리 (SPEC 5.4)")
  class Josa {

    @Test
    void 받침에_따라_은는이_달라진다() {
      assertThat(RestaurantSearchService.josa("새우")).isEqualTo("새우는");
      assertThat(RestaurantSearchService.josa("밀")).isEqualTo("밀은");
      assertThat(RestaurantSearchService.josa("대두")).isEqualTo("대두는");
    }

    @Test
    @DisplayName("이/가를 받침에 맞게 붙인다 — '밀이(가)' 로 보내지 않는다")
    void subjectParticle() {
      assertThat(RestaurantSearchService.subject("밀")).isEqualTo("밀이");
      assertThat(RestaurantSearchService.subject("새우")).isEqualTo("새우가");
      // 여러 개면 마지막 단어 기준
      assertThat(RestaurantSearchService.subject("나트륨 · 당류")).isEqualTo("나트륨 · 당류가");
    }

    @Test
    @DisplayName("밥·면 요청 문구는 그 음식에만 제안한다 — 버거에 '밥은 반만' 금지")
    void carbPhraseFitsMenu() {
      assertThat(RestaurantSearchService.fitsMenu("밥은 반만 주세요", "불고기버거")).isFalse();
      assertThat(RestaurantSearchService.fitsMenu("면은 반만 주세요", "불고기버거")).isFalse();
      assertThat(RestaurantSearchService.fitsMenu("밥은 반만 주세요", "김치볶음밥")).isTrue();
      assertThat(RestaurantSearchService.fitsMenu("면은 반만 주세요", "비빔냉면")).isTrue();
      assertThat(RestaurantSearchService.fitsMenu("밥은 반만 주세요", "비빔냉면")).isFalse();
      assertThat(RestaurantSearchService.fitsMenu("밥은 반만 주세요", "동태탕")).isFalse();
      // 탄수화물이 아닌 문구는 그대로 둔다
      assertThat(RestaurantSearchService.fitsMenu("국물은 따로 담아 주세요", "불고기버거")).isTrue();
      assertThat(RestaurantSearchService.josa("고등어")).isEqualTo("고등어는");
    }

    @Test
    @DisplayName("주소는 시군구·읍면동만 남긴다")
    void shortenAddress() {
      assertThat(RestaurantSearchService.shortenAddress("강원특별자치도 강릉시 초당동 123"))
          .isEqualTo("강릉시 초당동");
      assertThat(RestaurantSearchService.shortenAddress(null)).isEmpty();
    }
  }

  @Nested
  @DisplayName("미태깅 메뉴 — 안전하다고 답하지 않는다")
  class Untagged {

    @Test
    @DisplayName("사전에 없는 메뉴는 이름만 등록되어 태깅을 기다린다")
    void unknownDishIsQueued() {
      tour.menu(near, "처음보는메뉴", null);

      var view = search.detail(null, id(near), null, null).menus().get(0);

      assertThat(view.tagStatus()).isEqualTo("PENDING");
      assertThat(dishes.findByNormalizedName("처음보는메뉴"))
          .hasValueSatisfying(d -> assertThat(d.isTagged()).isFalse());
    }

    @Test
    @DisplayName("아직 태깅되지 않은 메뉴는 PENDING — 판정하지 않는다")
    void pendingMenu() {
      tour.menu(near, "미분석메뉴", null);
      Long userId = signup("u6", Set.of("당뇨"), Set.of());

      var view = search.detail(userId, id(near), null, null).menus().get(0);

      assertThat(view.tagStatus()).isEqualTo("PENDING");
      assertThat(view.seal()).isNull();
      assertThat(view.detail()).contains("아직 분석");
    }

    @Test
    @DisplayName("메뉴가 전부 미태깅이면 식당 판정을 내지 않는다")
    void allUntaggedMeansNoVerdict() {
      // 실제로 겪은 문제: 태깅 전 강릉 식당 10곳이 전부 ○ '조절 불필요' 로 나왔다.
      // 태그가 없는 것과 '걸리는 게 없는' 것은 다르다.
      tour.menu(near, "미분석메뉴", null);
      Long userId = signup("un1", Set.of("고혈압"), Set.of("새우"));

      var result = search.search(userId, LAT, LNG, 2_000, null, null, 0, 10);

      assertThat(result.items().get(0).seal()).isNull();
    }

    @Test
    @DisplayName("태깅된 메뉴가 하나라도 있으면 그것만으로 판정한다")
    void judgesOnTaggedOnly() {
      analyzed("물회", Set.of(), Set.of("새우"), Set.of());
      tour.menu(near, "미분석메뉴, 물회", null);
      Long userId = signup("un2", Set.of(), Set.of("새우"));

      var result = search.search(userId, LAT, LNG, 2_000, null, null, 0, 10);

      // 태깅된 메뉴가 물회(RED) 하나뿐이므로 식당도 RED
      assertThat(result.items().get(0).seal().verdict()).isEqualTo("RED");
    }

    @Test
    @DisplayName("상세에서도 미태깅 메뉴는 식당 판정에 끼지 않는다")
    void detailExcludesUntagged() {
      analyzed("물회", Set.of(), Set.of("새우"), Set.of());
      tour.menu(near, "미분석메뉴, 물회", null);
      Long userId = signup("un3", Set.of(), Set.of("새우"));

      var detail = search.detail(userId, id(near), null, null);

      assertThat(detail.seal().verdict()).isEqualTo("RED");
      assertThat(detail.menus()).hasSize(2);
      assertThat(detail.menus()).filteredOn(m -> "PENDING".equals(m.tagStatus()))
          .hasSize(1);
    }
  }
}
