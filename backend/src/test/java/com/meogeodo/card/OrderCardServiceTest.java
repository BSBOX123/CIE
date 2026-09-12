package com.meogeodo.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.meogeodo.card.CardDtos.CreateRequest;
import com.meogeodo.card.CardDtos.PolishResponse;
import com.meogeodo.card.CardDtos.PolishedPhrase;
import com.meogeodo.domain.Dish;
import com.meogeodo.domain.DishRepository;
import com.meogeodo.domain.DishTag;
import com.meogeodo.domain.DishTagRepository;
import com.meogeodo.domain.Menu;
import com.meogeodo.domain.MenuRepository;
import com.meogeodo.domain.Restaurant;
import com.meogeodo.domain.RestaurantRepository;
import com.meogeodo.user.AppUser;
import com.meogeodo.user.UserService;
import com.meogeodo.web.AuthDtos.ProfileUpdateRequest;
import com.meogeodo.web.AuthDtos.SignupRequest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** 주문요청카드 테스트 (MVP 3번). */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrderCardServiceTest {

  @Autowired private OrderCardService cards;
  @Autowired private UserService userService;
  @Autowired private RestaurantRepository restaurants;
  @Autowired private MenuRepository menus;
  @Autowired private DishRepository dishes;
  @Autowired private DishTagRepository dishTags;
  @Autowired private EntityManager em;

  @MockitoBean private CardPolishClient polisher;

  private AppUser signup(String loginId, Set<String> diseases, Set<String> allergies) {
    return userService.signup(new SignupRequest(
        loginId, "secret123", "김영수", "남성", 1958, "A형",
        diseases, Set.of(), allergies, false, Set.of("혈압약"), null, false));
  }

  private Menu menuWithTags(String restaurantName, String menuName,
      Set<String> cares, Set<String> traceAllergens) {
    Restaurant r = new Restaurant("c-" + restaurantName, restaurantName);
    r.setLat(BigDecimal.valueOf(37.79));
    r.setLng(BigDecimal.valueOf(128.89));
    restaurants.save(r);

    Dish dish = dishes.save(new Dish(menuName + "-dish"));
    dish.setTaggedAt(OffsetDateTime.now());
    dishes.save(dish);
    Menu m = new Menu(r, menuName, true);
    m.setDish(dish);
    menus.save(m);

    cares.forEach(c -> dishTags.save(new DishTag(
        dish, DishTag.TagType.CARE, c, DishTag.Source.NUTRITION_DB, BigDecimal.ONE)));
    traceAllergens.forEach(a -> {
      DishTag t = new DishTag(dish, DishTag.TagType.ALLERGEN, a,
          DishTag.Source.LLM, new BigDecimal("0.9"));
      t.setAmount(DishTag.Amount.TRACE);
      dishTags.save(t);
    });
    em.flush();
    return m;
  }

  /** 다듬기를 통과시키는 대역. */
  private void givenPolishEchoes() {
    when(polisher.polish(any(), any(), any())).thenAnswer(inv -> {
      List<String> phrases = inv.getArgument(0);
      return new PolishResponse(
          phrases.stream().map(p -> new PolishedPhrase(p, p + " (다듬음)", false)).toList(),
          "gemini-3.5-flash-lite");
    });
  }

  @Nested
  @DisplayName("카드 내용")
  class Content {

    @Test
    @DisplayName("알레르기 배너와 질환 줄이 채워진다")
    void bannerAndDiseases() {
      var user = signup("card1", Set.of("당뇨", "고혈압"), Set.of("새우", "고등어"));
      when(polisher.polish(any(), any(), any())).thenReturn(null);

      var card = cards.create(user.getId(),
          new CreateRequest(null, null, List.of("국물은 따로 담아 주세요")));

      assertThat(card.allergyBanner()).contains("새우").contains("고등어")
          .endsWith("알레르기가 있습니다");
      assertThat(card.diseaseLine()).contains("당뇨").contains("고혈압");
      assertThat(card.footer()).containsExactly(
          "이 손님은 위 재료를 피해야 합니다.", "확인이 어려우면 알려 주세요.");
    }

    @Test
    @DisplayName("알레르기가 없으면 배너를 비운다")
    void noAllergyNoBanner() {
      var user = signup("card2", Set.of("당뇨"), Set.of());
      when(polisher.polish(any(), any(), any())).thenReturn(null);

      var card = cards.create(user.getId(), new CreateRequest(null, null, List.of("밥은 반만 주세요")));

      assertThat(card.allergyBanner()).isNull();
    }

    @Test
    @DisplayName("질환이 없으면 '질환 없음'")
    void noDisease() {
      var user = signup("card3", Set.of(), Set.of("새우"));
      when(polisher.polish(any(), any(), any())).thenReturn(null);

      assertThat(cards.create(user.getId(), new CreateRequest(null, null, List.of("a")))
          .diseaseLine()).isEqualTo("질환 없음");
    }

    @Test
    @DisplayName("메뉴를 지정하면 '식당 · 메뉴' 로 찍힌다")
    void menuLine() {
      var user = signup("card4", Set.of(), Set.of());
      Menu m = menuWithTags("초당할머니순두부", "순두부 백반", Set.of(), Set.of());
      when(polisher.polish(any(), any(), any())).thenReturn(null);

      var card = cards.create(user.getId(),
          new CreateRequest(m.getRestaurant().getId(), m.getId(), List.of("a")));

      assertThat(card.menuLine()).isEqualTo("초당할머니순두부 · 순두부 백반");
    }

    @Test
    @DisplayName("요청이 없으면 '특별한 요청은 없습니다'")
    void noRequests() {
      var user = signup("card5", Set.of(), Set.of());
      var card = cards.create(user.getId(), new CreateRequest(null, null, List.of()));

      assertThat(card.requests()).extracting(CardDtos.CardRequest::phrase)
          .containsExactly("특별한 요청은 없습니다");
    }
  }

  @Nested
  @DisplayName("복용약 — 민감정보 (SPEC 11.4)")
  class Medications {

    @Test
    @DisplayName("기본적으로 카드에 인쇄하지 않는다")
    void hiddenByDefault() {
      var user = signup("med1", Set.of(), Set.of());
      when(polisher.polish(any(), any(), any())).thenReturn(null);

      assertThat(cards.create(user.getId(), new CreateRequest(null, null, List.of("a")))
          .medsLine()).isNull();
    }

    @Test
    @DisplayName("사용자가 켰을 때만 인쇄한다")
    void shownWhenOptedIn() {
      var user = signup("med2", Set.of(), Set.of());
      userService.update(user, new ProfileUpdateRequest(
          null, null, null, null, null, null, null, null, null, null, true, null));
      when(polisher.polish(any(), any(), any())).thenReturn(null);

      assertThat(cards.create(user.getId(), new CreateRequest(null, null, List.of("a")))
          .medsLine()).contains("혈압약");
    }
  }

  @Nested
  @DisplayName("문구 다듬기 — LLM 은 표현만 손댄다")
  class Polish {

    @Test
    @DisplayName("다듬은 문구가 카드에 들어가고 원문도 남는다")
    void polished() {
      var user = signup("pol1", Set.of(), Set.of("새우"));
      givenPolishEchoes();

      var card = cards.create(user.getId(),
          new CreateRequest(null, null, List.of("새우는 빼 주세요")));

      assertThat(card.requests().get(0).phrase()).isEqualTo("새우는 빼 주세요 (다듬음)");
      assertThat(card.requests().get(0).original()).isEqualTo("새우는 빼 주세요");
      assertThat(card.requests().get(0).polished()).isTrue();
    }

    @Test
    @DisplayName("ai-service 가 죽어도 카드는 만들어진다")
    void survivesPolisherFailure() {
      var user = signup("pol2", Set.of(), Set.of("새우"));
      when(polisher.polish(any(), any(), any())).thenReturn(null);

      var card = cards.create(user.getId(),
          new CreateRequest(null, null, List.of("새우는 빼 주세요")));

      assertThat(card.requests().get(0).phrase()).isEqualTo("새우는 빼 주세요");
      assertThat(card.requests().get(0).polished()).isFalse();
    }

    @Test
    @DisplayName("개수가 맞지 않으면 전부 원문을 쓴다")
    void countMismatchFallsBack() {
      var user = signup("pol3", Set.of(), Set.of());
      when(polisher.polish(any(), any(), any())).thenReturn(
          new PolishResponse(List.of(new PolishedPhrase("a", "합쳐진 문구", false)), "m"));

      var card = cards.create(user.getId(),
          new CreateRequest(null, null, List.of("새우는 빼 주세요", "국물은 따로 담아 주세요")));

      assertThat(card.requests()).extracting(CardDtos.CardRequest::phrase)
          .containsExactly("새우는 빼 주세요", "국물은 따로 담아 주세요");
    }

    @Test
    @DisplayName("알레르겐 이름을 다듬기 대상에서 지켜야 할 낱말로 넘긴다")
    void passesAllergensAsMustKeep() {
      var user = signup("pol4", Set.of(), Set.of("새우", "고등어"));
      givenPolishEchoes();

      cards.create(user.getId(), new CreateRequest(null, null, List.of("새우는 빼 주세요")));

      org.mockito.Mockito.verify(polisher).polish(
          any(),
          org.mockito.ArgumentMatchers.argThat(
              keep -> keep.containsAll(List.of("새우", "고등어"))),
          any());
    }
  }

  @Nested
  @DisplayName("자동 채움")
  class AutoFill {

    @Test
    @DisplayName("요청을 안 보내면 메뉴 판정에서 제안 문구를 가져온다")
    void fillsFromMenuVerdict() {
      var user = signup("auto1", Set.of("고혈압"), Set.of("대두"));
      Menu m = menuWithTags("순두부집", "순두부찌개", Set.of("나트륨"), Set.of("대두"));
      when(polisher.polish(any(), any(), any())).thenReturn(null);

      var card = cards.create(user.getId(),
          new CreateRequest(m.getRestaurant().getId(), m.getId(), null));

      assertThat(card.requests()).extracting(CardDtos.CardRequest::phrase)
          .contains("대두는 빼 주세요")
          .anyMatch(p -> p.contains("국물"));
    }
  }

  @Nested
  @DisplayName("접근 제어")
  class Access {

    @Test
    @DisplayName("남의 카드는 볼 수 없다 — 건강정보가 들어 있다")
    void cannotReadOthersCard() {
      var owner = signup("own1", Set.of("당뇨"), Set.of("새우"));
      var other = signup("own2", Set.of(), Set.of());
      when(polisher.polish(any(), any(), any())).thenReturn(null);

      Long cardId = cards.create(owner.getId(),
          new CreateRequest(null, null, List.of("a"))).id();

      assertThatThrownBy(() -> cards.get(other.getId(), cardId))
          .hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    @DisplayName("본인 카드는 다시 조회된다")
    void ownerCanRead() {
      var user = signup("own3", Set.of("당뇨"), Set.of("새우"));
      when(polisher.polish(any(), any(), any())).thenReturn(null);

      Long cardId = cards.create(user.getId(),
          new CreateRequest(null, null, List.of("새우는 빼 주세요"))).id();
      em.flush();
      em.clear();

      var card = cards.get(user.getId(), cardId);
      assertThat(card.requests()).extracting(CardDtos.CardRequest::phrase)
          .containsExactly("새우는 빼 주세요");
      assertThat(card.allergyBanner()).contains("새우");
    }

    @Test
    @DisplayName("비로그인은 만들 수 없다")
    void anonymousCannotCreate() {
      assertThatThrownBy(() -> cards.create(null, new CreateRequest(null, null, List.of("a"))))
          .isInstanceOf(UserService.UnauthorizedException.class);
    }
  }
}
