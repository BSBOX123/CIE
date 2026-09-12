package com.meogeodo.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.meogeodo.domain.Dish;
import com.meogeodo.domain.DishRepository;
import com.meogeodo.domain.DishTag;
import com.meogeodo.domain.DishTagRepository;
import com.meogeodo.domain.Menu;
import com.meogeodo.domain.MenuRepository;
import com.meogeodo.domain.Restaurant;
import com.meogeodo.domain.RestaurantRepository;
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

/**
 * GPS 반경 검색 테스트 (MVP 2번).
 *
 * <p>좌표는 강릉 실제 데이터에서 가져왔다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RestaurantSearchServiceTest {

  // 경포대 기준점
  private static final double LAT = 37.7952, LNG = 128.8964;

  @Autowired private RestaurantSearchService search;
  @Autowired private RestaurantRepository restaurants;
  @Autowired private MenuRepository menus;
  @Autowired private DishRepository dishes;
  @Autowired private DishTagRepository dishTags;
  @Autowired private UserService userService;
  @Autowired private EntityManager em;

  private Restaurant near;
  private Restaurant far;

  @BeforeEach
  void setUp() {
    near = restaurant("가까운집", 37.7960, 128.8970);   // 약 100m
    far = restaurant("먼집", 37.8500, 128.9500);         // 약 8km
  }

  private Restaurant restaurant(String name, double lat, double lng) {
    Restaurant r = new Restaurant("c-" + name, name);
    r.setLat(BigDecimal.valueOf(lat));
    r.setLng(BigDecimal.valueOf(lng));
    r.setArea("강릉 경포동");
    return restaurants.save(r);
  }

  /** 태깅된 메뉴를 붙인다. */
  private void menu(Restaurant r, String name, Set<String> cares,
      Set<String> mainAllergens, Set<String> traceAllergens) {
    Dish dish = dishes.save(new Dish(name + "-" + r.getId()));
    dish.setTaggedAt(OffsetDateTime.now());
    dishes.save(dish);
    Menu m = new Menu(r, name, true);
    m.setDish(dish);
    menus.save(m);

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

  private Long signup(String loginId, Set<String> diseases, Set<String> allergies) {
    return userService.signup(new SignupRequest(
        loginId, "secret123", "김영수", "남성", 1958, "A형",
        diseases, Set.of(), allergies, false, Set.of(), null, false)).getId();
  }

  @Nested
  @DisplayName("반경 검색")
  class Radius {

    @Test
    @DisplayName("반경 안의 식당만 나온다")
    void withinRadius() {
      var result = search.search(null, LAT, LNG, 2_000, null, null, 0, 10);

      assertThat(result.items()).extracting(SearchDtos.RestaurantSummary::name)
          .containsExactly("가까운집");
    }

    @Test
    @DisplayName("반경을 넓히면 먼 곳도 포함된다")
    void widerRadius() {
      var result = search.search(null, LAT, LNG, 20_000, null, null, 0, 10);
      assertThat(result.items()).hasSize(2);
    }

    @Test
    @DisplayName("거리순으로 정렬된다")
    void sortedByDistance() {
      var result = search.search(null, LAT, LNG, 20_000, null, null, 0, 10);
      assertThat(result.items()).extracting(SearchDtos.RestaurantSummary::distanceM)
          .isSorted();
    }

    @Test
    @DisplayName("거리와 도보 시간을 함께 준다")
    void distanceAndWalk() {
      var item = search.search(null, LAT, LNG, 2_000, null, null, 0, 10).items().get(0);
      assertThat(item.distanceM()).isBetween(1, 300);
      assertThat(item.walkMinutes()).isGreaterThanOrEqualTo(1);
      assertThat(item.meta()).contains("강릉 경포동").contains("도보");
    }

    @Test
    @DisplayName("좌표가 없는 식당은 제외한다 — 지도에 찍을 수 없다")
    void skipsMissingCoordinates() {
      Restaurant noCoords = new Restaurant("c-noloc", "좌표없는집");
      restaurants.save(noCoords);
      em.flush();

      var result = search.search(null, LAT, LNG, 20_000, null, null, 0, 10);
      assertThat(result.items()).extracting(SearchDtos.RestaurantSummary::name)
          .doesNotContain("좌표없는집");
    }

    @Test
    @DisplayName("이름으로 거를 수 있다")
    void filterByQuery() {
      var result = search.search(null, LAT, LNG, 20_000, "먼", null, 0, 10);
      assertThat(result.items()).extracting(SearchDtos.RestaurantSummary::name)
          .containsExactly("먼집");
    }

    @Test
    @DisplayName("페이지 기본 크기는 3 (프론트 PER_PAGE)")
    void defaultPageSize() {
      for (int i = 0; i < 5; i++) {
        restaurant("집" + i, 37.7955 + i * 0.0001, 128.8965);
      }
      em.flush();

      var result = search.search(null, LAT, LNG, 2_000, null, null, 0, 0);
      assertThat(result.size()).isEqualTo(3);
      assertThat(result.items()).hasSize(3);
      assertThat(result.totalCount()).isGreaterThan(3);
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
  }

  @Nested
  @DisplayName("상세")
  class Detail {

    @Test
    @DisplayName("메뉴별 판정과 요청 문구를 준다")
    void menuVerdicts() {
      menu(near, "순두부백반", Set.of("나트륨"), Set.of(), Set.of("대두"));
      Long userId = signup("u4", Set.of("고혈압"), Set.of("대두"));

      var detail = search.detail(userId, near.getId(), LAT, LNG);
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

      var m = search.detail(userId, near.getId(), null, null).menus().get(0);

      assertThat(m.seal().verdict()).isEqualTo("RED");
      assertThat(m.detail()).contains("주재료").contains("다른 메뉴");
    }

    @Test
    @DisplayName("아직 태깅되지 않은 메뉴는 PENDING — 판정하지 않는다")
    void pendingMenu() {
      Dish untagged = dishes.save(new Dish("미분석음식"));
      Menu m = new Menu(near, "미분석메뉴", true);
      m.setDish(untagged);
      menus.save(m);
      em.flush();

      Long userId = signup("u6", Set.of("당뇨"), Set.of());
      var view = search.detail(userId, near.getId(), null, null).menus().get(0);

      assertThat(view.tagStatus()).isEqualTo("PENDING");
      assertThat(view.seal()).isNull();
      assertThat(view.detail()).contains("아직 분석");
    }

    @Test
    @DisplayName("좌표를 안 주면 거리는 비어 있다")
    void detailWithoutCoordinates() {
      var detail = search.detail(null, near.getId(), null, null);
      assertThat(detail.distanceM()).isNull();
      assertThat(detail.walkMinutes()).isNull();
    }

    @Test
    @DisplayName("주의성분 안내 문구가 함께 온다")
    void careNote() {
      menu(near, "김치찌개", Set.of("나트륨"), Set.of(), Set.of());
      Long userId = signup("u7", Set.of("고혈압"), Set.of());

      var m = search.detail(userId, near.getId(), null, null).menus().get(0);
      assertThat(m.detail()).contains("국물");
    }

    @Test
    @DisplayName("면책 문구가 붙는다 (SPEC 11.1)")
    void disclaimer() {
      assertThat(search.detail(null, near.getId(), null, null).disclaimer())
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
      assertThat(RestaurantSearchService.josa("고등어")).isEqualTo("고등어는");
    }
  }

  @Nested
  @DisplayName("미태깅 메뉴 — 안전하다고 답하지 않는다")
  class Untagged {

    /** 태깅 전 메뉴만 있는 식당. */
    private void untaggedMenu(Restaurant r, String name) {
      Dish d = dishes.save(new Dish(name + "-" + r.getId()));  // tagged_at 없음
      Menu m = new Menu(r, name, true);
      m.setDish(d);
      menus.save(m);
      em.flush();
    }

    @Test
    @DisplayName("메뉴가 전부 미태깅이면 식당 판정을 내지 않는다")
    void allUntaggedMeansNoVerdict() {
      // 실제로 겪은 문제: 태깅 전 강릉 식당 10곳이 전부 ○ '조절 불필요' 로 나왔다.
      // 태그가 없는 것과 '걸리는 게 없는' 것은 다르다.
      untaggedMenu(near, "미분석메뉴");
      Long userId = signup("un1", Set.of("고혈압"), Set.of("새우"));

      var result = search.search(userId, LAT, LNG, 2_000, null, null, 0, 10);

      assertThat(result.items().get(0).seal()).isNull();
    }

    @Test
    @DisplayName("태깅된 메뉴가 하나라도 있으면 그것만으로 판정한다")
    void judgesOnTaggedOnly() {
      untaggedMenu(near, "미분석메뉴");
      menu(near, "물회", Set.of(), Set.of("새우"), Set.of());
      Long userId = signup("un2", Set.of(), Set.of("새우"));

      var result = search.search(userId, LAT, LNG, 2_000, null, null, 0, 10);

      // 태깅된 메뉴가 물회(RED) 하나뿐이므로 식당도 RED
      assertThat(result.items().get(0).seal().verdict()).isEqualTo("RED");
    }

    @Test
    @DisplayName("상세에서도 미태깅 메뉴는 식당 판정에 끼지 않는다")
    void detailExcludesUntagged() {
      untaggedMenu(near, "미분석메뉴");
      menu(near, "물회", Set.of(), Set.of("새우"), Set.of());
      Long userId = signup("un3", Set.of(), Set.of("새우"));

      var detail = search.detail(userId, near.getId(), null, null);

      assertThat(detail.seal().verdict()).isEqualTo("RED");
      assertThat(detail.menus()).hasSize(2);
      assertThat(detail.menus()).filteredOn(m -> "PENDING".equals(m.tagStatus()))
          .hasSize(1);
    }
  }
}
