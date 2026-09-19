package com.meogeodo.card;

import com.meogeodo.card.CardDtos.CardRequest;
import com.meogeodo.card.CardDtos.CardResponse;
import com.meogeodo.card.CardDtos.CreateRequest;
import com.meogeodo.card.CardDtos.PolishResponse;
import com.meogeodo.card.CardDtos.PolishedPhrase;
import com.meogeodo.domain.OrderCard;
import com.meogeodo.domain.OrderCardRepository;
import com.meogeodo.search.RestaurantSearchService;
import com.meogeodo.search.SearchDtos.MenuView;
import com.meogeodo.search.SearchDtos.RestaurantDetail;
import com.meogeodo.tour.TourApiException;
import com.meogeodo.user.AppUser;
import com.meogeodo.user.AppUserRepository;
import com.meogeodo.user.UserService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 주문요청카드 조립 (SPEC 9.5, MVP 3번).
 *
 * <p>카드는 매장 직원에게 건네는 물건이다. 서버가 내용을 정하고, LLM은 표현만
 * 다듬으며, 그림 그리기는 클라이언트가 한다.
 */
@Service
public class OrderCardService {

  private static final Logger log = LoggerFactory.getLogger(OrderCardService.class);

  private static final List<String> FOOTER =
      List.of("이 손님은 위 재료를 피해야 합니다.", "확인이 어려우면 알려 주세요.");

  private static final int MAX_REQUESTS = 20;

  private final OrderCardRepository cards;
  private final AppUserRepository users;
  private final RestaurantSearchService search;
  private final CardPolishClient polisher;

  public OrderCardService(
      OrderCardRepository cards,
      AppUserRepository users,
      RestaurantSearchService search,
      CardPolishClient polisher) {
    this.cards = cards;
    this.users = users;
    this.search = search;
    this.polisher = polisher;
  }

  @Transactional
  public CardResponse create(Long userId, CreateRequest request) {
    AppUser user = requireUser(userId);

    // 식당·메뉴는 저장하지 않으므로 관광공사에서 다시 받아 메뉴 id 로 찾는다.
    // 상세 화면과 같은 경로라 판정·제안 문구도 화면에서 본 것과 같다.
    RestaurantDetail restaurant = findRestaurant(userId, request.restaurantId());
    MenuView menu = restaurant == null || request.menuId() == null
        ? null
        : restaurant.menus().stream()
            .filter(m -> m.id().equals(request.menuId()))
            .findFirst()
            .orElse(null);

    String menuLine = menuLine(restaurant, menu);
    List<String> phrases = resolveRequests(request, menu);
    List<CardRequest> printed = polish(phrases, user.getAllergies(), menuLine);

    OrderCard card = new OrderCard(
        userId,
        restaurant == null ? null : restaurant.id(),
        menu == null ? null : menu.id(),
        menuLine);
    card.setRequests(printed.stream().map(CardRequest::phrase).toList());
    cards.save(card);

    return render(card.getId(), user, menuLine, printed);
  }

  @Transactional(readOnly = true)
  public CardResponse get(Long userId, Long cardId) {
    AppUser user = requireUser(userId);
    // 남의 카드를 볼 수 없다. 건강정보가 들어 있다.
    OrderCard card = cards.findByIdAndUserId(cardId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "카드를 찾을 수 없습니다"));

    List<CardRequest> printed =
        card.getRequests().stream().map(p -> new CardRequest(p, p, false)).toList();
    return render(card.getId(), user, card.getMenuLabel(), printed);
  }

  // ── 조립 ──────────────────────────────────────────────────────────

  /**
   * 카드에 넣을 요청 문구를 정한다.
   *
   * <p>사용자가 화면에서 골라 보냈으면 그대로 쓴다. 안 보냈으면 이 메뉴에
   * 실제로 도움이 되는 문구(판정 결과)와 상용 문구를 합쳐 채운다.
   */
  private List<String> resolveRequests(CreateRequest request, MenuView menu) {

    if (request.requests() != null && !request.requests().isEmpty()) {
      return request.requests().stream()
          .filter(p -> p != null && !p.isBlank())
          .map(String::trim)
          .distinct()
          .limit(MAX_REQUESTS)
          .toList();
    }

    Set<String> out = new LinkedHashSet<>();
    if (menu != null) {
      // 이 메뉴에 대한 판정에서 나온 제안을 먼저 넣는다.
      out.addAll(menu.suggestedRequests());
    }
    return out.stream().limit(MAX_REQUESTS).toList();
  }

  /**
   * 문구를 다듬는다.
   *
   * <p>실패해도 카드는 만들어진다. 다듬기는 있으면 좋은 것이지 반드시 필요한
   * 것이 아니다.
   */
  private List<CardRequest> polish(
      List<String> phrases, Set<String> allergies, String menuLine) {

    if (phrases.isEmpty()) {
      return List.of();
    }
    PolishResponse response = polisher.polish(phrases, List.copyOf(allergies), menuLine);
    if (response == null || response.phrases() == null
        || response.phrases().size() != phrases.size()) {
      if (response != null) {
        log.warn("문구 다듬기 결과 개수가 맞지 않아 원문을 씁니다");
      }
      return phrases.stream().map(p -> new CardRequest(p, p, false)).toList();
    }

    List<CardRequest> out = new ArrayList<>();
    for (PolishedPhrase p : response.phrases()) {
      out.add(new CardRequest(p.polished(), p.original(), !p.fellBack()));
    }
    return out;
  }

  private CardResponse render(
      Long cardId, AppUser user, String menuLine, List<CardRequest> requests) {

    Set<String> allergies = user.getAllergies();
    String banner = allergies.isEmpty()
        ? null
        : String.join(" · ", allergies) + " 알레르기가 있습니다";

    Set<String> diseases = user.getDiseases();
    String diseaseLine = diseases.isEmpty() ? "질환 없음" : String.join(" · ", diseases);

    // 복용약은 사용자가 명시적으로 켠 경우에만 인쇄한다. 카드는 매장에 건네는
    // 물건이고 복용약은 민감정보다 (SPEC 11.4).
    String medsLine = null;
    if (user.getProfile() != null && user.getProfile().isShowMedsOnCard()
        && !user.getMedications().isEmpty()) {
      medsLine = "복용 중 — " + String.join(" · ", user.getMedications());
    }

    return new CardResponse(
        cardId, banner, diseaseLine, menuLine,
        requests.isEmpty()
            ? List.of(new CardRequest("특별한 요청은 없습니다", "특별한 요청은 없습니다", false))
            : requests,
        medsLine, FOOTER, UserService.DISCLAIMER);
  }

  /**
   * 카드에 실을 식당.
   *
   * <p>관광공사를 잠시 못 부르면 식당 줄 없이 카드를 만든다. 카드는 매장 앞에서
   * 바로 써야 하는 물건이라, 식당 이름 한 줄 때문에 통째로 실패하는 것보다 낫다.
   */
  private RestaurantDetail findRestaurant(Long userId, Long restaurantId) {
    if (restaurantId == null) {
      return null;
    }
    try {
      return search.detail(userId, restaurantId, null, null);
    } catch (ResponseStatusException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        return null;
      }
      throw e;
    } catch (TourApiException e) {
      log.warn("카드 식당 조회 실패 — 식당 줄 없이 만듭니다: {}", e.getMessage());
      return null;
    }
  }

  private String menuLine(RestaurantDetail restaurant, MenuView menu) {
    if (restaurant == null) {
      return null;
    }
    if (menu == null) {
      return restaurant.name();
    }
    return restaurant.name() + " · " + menu.name();
  }

  private AppUser requireUser(Long userId) {
    if (userId == null) {
      throw new UserService.UnauthorizedException();
    }
    return users.findById(userId).orElseThrow(UserService.UnauthorizedException::new);
  }
}
