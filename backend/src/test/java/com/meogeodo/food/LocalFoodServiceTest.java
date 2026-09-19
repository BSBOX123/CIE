package com.meogeodo.food;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meogeodo.domain.Dish;
import com.meogeodo.domain.DishRepository;
import com.meogeodo.domain.DishTag;
import com.meogeodo.domain.DishTagRepository;
import com.meogeodo.domain.LocalFood;
import com.meogeodo.domain.LocalFoodRepository;
import com.meogeodo.domain.LocalFoodTip;
import com.meogeodo.domain.LocalFoodTipRepository;
import com.meogeodo.search.SearchDtos.RestaurantSummary;
import com.meogeodo.tour.FakeTourApi;
import com.meogeodo.tour.TourApiException;
import com.meogeodo.user.UserService;
import com.meogeodo.web.AuthDtos.SignupRequest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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

/** 지역 음식 테스트 (SPEC 9.3). 좌표는 부산 해운대. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LocalFoodServiceTest {

  private static final double LAT = 35.1587, LNG = 129.1604;

  @Autowired private LocalFoodService service;
  @Autowired private FakeTourApi tour;
  @Autowired private LocalFoodRepository foods;
  @Autowired private LocalFoodTipRepository tips;
  @Autowired private DishRepository dishes;
  @Autowired private DishTagRepository dishTags;
  @Autowired private UserService userService;
  @Autowired private EntityManager em;

  @BeforeEach
  void setUp() {
    tour.reset();
    Dish milmyeon = taggedDish("밀면-lf", "밀", DishTag.Amount.MAIN);
    Dish mulhoe = taggedDish("물회-lf", "오징어", DishTag.Amount.MAIN);
    Dish pending = dishes.save(new Dish("돼지국밥-lf"));

    foods.save(new LocalFood("milmyeon", "밀면", milmyeon.getId(), "26", "부산",
        "부산식 냉면", "밀면", 10));
    foods.save(new LocalFood("dwaeji-gukbap", "돼지국밥", pending.getId(), "26", "부산",
        "돼지국밥", "돼지국밥", 20));
    foods.save(new LocalFood("mulhoe", "물회", mulhoe.getId(), "47", "경북 포항",
        "포항 물회", "물회", 30));
    tips.save(new LocalFoodTip("milmyeon", "육수는 조금만 담아 주세요", 1));
    em.flush();
  }

  private Dish taggedDish(String name, String allergen, DishTag.Amount amount) {
    Dish dish = dishes.save(new Dish(name));
    dish.setTaggedAt(OffsetDateTime.now());
    DishTag t = new DishTag(dish, DishTag.TagType.ALLERGEN, allergen, DishTag.Source.LLM,
        new BigDecimal("0.9"));
    t.setAmount(amount);
    dishTags.save(t);
    return dish;
  }

  private Long signup(String loginId, Set<String> allergies) {
    return userService.signup(new SignupRequest(
        loginId, "secret123", "김영수", "남성", 1958, "A형",
        Set.of(), Set.of(), allergies, false, Set.of(), null, false)).getId();
  }

  /** 부산 해운대의 식당 하나. 지역 판정의 근거가 된다. */
  private String busanRestaurant(String title, double lat, double lng) {
    return tour.add(title, lat, lng, "부산광역시 해운대구 우동 1", "26");
  }

  @Nested
  @DisplayName("지역 판정")
  class Region {

    @Test
    @DisplayName("가장 가까운 식당의 시도를 이어받는다 (SPEC 1.1.1)")
    void inheritsNearestRestaurantRegion() {
      busanRestaurant("해운대횟집", 35.1590, 129.1600);

      var result = service.list(null, LAT, LNG);

      assertThat(result.region()).isEqualTo("부산");
      assertThat(result.district()).isEqualTo("해운대구");
      assertThat(result.regionCode()).isEqualTo("26");
    }

    @Test
    @DisplayName("내 시도 음식만 나온다 — 부산에서 경북 물회를 섞지 않는다")
    void onlyMyRegion() {
      busanRestaurant("해운대횟집", 35.1590, 129.1600);

      var items = service.list(null, LAT, LNG).items();

      assertThat(items).extracting(FoodDtos.FoodItem::id)
          .containsExactly("milmyeon", "dwaeji-gukbap");
    }

    @Test
    @DisplayName("같은 권역이라도 음식이 없는 시도면 빈 목록 — 옆 시도 음식으로 채우지 않는다")
    void coveredButNoFoodOfMine() {
      tour.add("동성로식당", 35.8714, 128.6014, "대구광역시 중구 동성로 1", "27");

      var result = service.list(null, 35.8714, 128.6014);

      assertThat(result.region()).isEqualTo("대구");
      assertThat(result.items()).isEmpty();
    }

    @Test
    @DisplayName("보유하지 않은 지역은 빈 목록 — 프론트가 섹션을 숨긴다 (SPEC 7.5)")
    void uncoveredRegion() {
      tour.add("초당순두부", LAT + 0.001, LNG, "강원특별자치도 강릉시 초당동", "51");

      var result = service.list(null, LAT, LNG);

      assertThat(result.region()).isEqualTo("강원");
      assertThat(result.items()).isEmpty();
    }

    @Test
    @DisplayName("전남광주통합특별시(12)의 옛 광주 구는 '광주', 나머지는 '전남'으로 보여 준다")
    void gwangjuJeonnam() {
      tour.add("충장로식당", 35.1470, 126.9190, "전남광주통합특별시 동구 충장로 1", "12");
      assertThat(service.list(null, 35.1470, 126.9190).region()).isEqualTo("광주");

      tour.reset();
      tour.add("여수식당", 34.7400, 127.7300, "전남광주통합특별시 여수시 중앙로 1", "12");
      assertThat(service.list(null, 34.7400, 127.7300).region()).isEqualTo("전남");
    }

    @Test
    @DisplayName("근처에 식당이 하나도 없으면 지역을 모른다고 답한다")
    void noRestaurantNearby() {
      var result = service.list(null, LAT, LNG);
      assertThat(result.region()).isNull();
      assertThat(result.items()).isEmpty();
    }
  }

  @Nested
  @DisplayName("판정")
  class Judgment {

    @Test
    @DisplayName("비로그인은 판정 없이 목록만")
    void anonymous() {
      busanRestaurant("해운대횟집", 35.1590, 129.1600);
      var item = service.list(null, LAT, LNG).items().get(0);
      assertThat(item.seal()).isNull();
      assertThat(item.summary()).isNull();
      assertThat(item.tagStatus()).isEqualTo("TAGGED");
    }

    @Test
    @DisplayName("밀 알레르기면 밀면은 ✕ — '밀 있음'")
    void allergyHit() {
      busanRestaurant("해운대횟집", 35.1590, 129.1600);
      Long userId = signup("lf1", Set.of("밀"));

      var item = service.list(userId, LAT, LNG).items().get(0);

      assertThat(item.seal().verdict()).isEqualTo("RED");
      assertThat(item.summary()).isEqualTo("밀 있음");
    }

    @Test
    @DisplayName("분석 전 음식은 판정하지 않는다 — 안전하다고 답하지 않는다")
    void pendingFood() {
      busanRestaurant("해운대횟집", 35.1590, 129.1600);
      Long userId = signup("lf2", Set.of("밀"));

      var item = service.list(userId, LAT, LNG).items().get(1);

      assertThat(item.id()).isEqualTo("dwaeji-gukbap");
      assertThat(item.tagStatus()).isEqualTo("PENDING");
      assertThat(item.seal()).isNull();
    }

    @Test
    @DisplayName("상세에 알레르기 안내와 요청 팁이 붙는다")
    void detailText() {
      Long userId = signup("lf3", Set.of("밀"));

      var detail = service.detail(userId, "milmyeon", null, null);

      assertThat(detail.regionLine()).isEqualTo("부산 지역 음식");
      assertThat(detail.hasAllergen()).isTrue();
      assertThat(detail.allergenText()).startsWith("밀 — 주재료");
      assertThat(detail.tips()).extracting(FoodDtos.Tip::phrase)
          .containsExactly("육수는 조금만 담아 주세요");
    }

    @Test
    @DisplayName("없는 지역 음식은 404")
    void unknown() {
      assertThatThrownBy(() -> service.detail(null, "nope", null, null))
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  @Nested
  @DisplayName("이 음식을 파는 곳 — 관광공사에서 실시간으로 찾는다")
  class Selling {

    @Test
    @DisplayName("가게 이름에 음식명이 들어간 곳을 가까운 순으로")
    void byTitle() {
      String far = busanRestaurant("초량밀면", 35.1150, 129.0400);   // 약 12km
      String near = busanRestaurant("해운대밀면", 35.1600, 129.1610); // 약 150m
      busanRestaurant("해운대횟집", 35.1590, 129.1600);

      var restaurants = service.detail(null, "milmyeon", LAT, LNG).restaurants();

      assertThat(restaurants).extracting(RestaurantSummary::id)
          .containsExactly(Long.valueOf(near), Long.valueOf(far));
    }

    @Test
    @DisplayName("다른 시도의 가게는 추천하지 않는다 — 대구에서 경주 가게를 권하지 않는다")
    void onlySameRegionRestaurants() {
      String busan = busanRestaurant("해운대밀면", 35.1600, 129.1610);
      tour.add("경주밀면", 35.1620, 129.1630, "경상북도 경주시 1", "47");  // 가까워도 다른 시도

      var restaurants = service.detail(null, "milmyeon", LAT, LNG).restaurants();

      assertThat(restaurants).extracting(RestaurantSummary::id)
          .containsExactly(Long.valueOf(busan));
    }

    @Test
    @DisplayName("내 시도에 파는 곳이 없으면 비운다 — 멀리 있는 다른 지역 가게로 채우지 않는다")
    void noShopInMyRegion() {
      busanRestaurant("해운대밀면", 35.1600, 129.1610);
      tour.add("동성로식당", 35.8714, 128.6014, "대구광역시 중구 동성로 1", "27");

      var restaurants = service.detail(null, "milmyeon", 35.8714, 128.6014).restaurants();

      assertThat(restaurants).isEmpty();
    }

    @Test
    @DisplayName("이름에 없어도 근처 식당 메뉴에 있으면 찾는다")
    void byMenu() {
      String shop = busanRestaurant("할매국수", 35.1591, 129.1602);
      tour.menu(shop, "비빔국수, 물 밀면 등", null);
      busanRestaurant("해운대횟집", 35.1590, 129.1600);

      var restaurants = service.detail(null, "milmyeon", LAT, LNG).restaurants();

      assertThat(restaurants).extracting(RestaurantSummary::name).containsExactly("할매국수");
    }

    @Test
    @DisplayName("좌표가 없으면 식당은 비운다")
    void noCoordinates() {
      busanRestaurant("해운대밀면", 35.1600, 129.1610);
      assertThat(service.detail(null, "milmyeon", null, null).restaurants()).isEmpty();
    }

    @Test
    @DisplayName("관광공사가 죽어도 음식 정보는 나온다 — 식당만 비우고 알린다")
    void tourDown() {
      tour.down(true);

      var detail = service.detail(null, "milmyeon", LAT, LNG);

      assertThat(detail.name()).isEqualTo("밀면");
      assertThat(detail.restaurants()).isEmpty();
      assertThat(detail.restaurantsUnavailable()).isTrue();
    }

    @Test
    @DisplayName("목록은 지역을 정해야 하므로 관광공사가 죽으면 실패로 알린다")
    void listFailsWhenTourDown() {
      tour.down(true);
      assertThatThrownBy(() -> service.list(null, LAT, LNG))
          .isInstanceOf(TourApiException.class);
    }
  }
}
