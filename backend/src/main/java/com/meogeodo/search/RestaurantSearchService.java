package com.meogeodo.search;

import com.meogeodo.domain.MenuQueryRepository;
import com.meogeodo.domain.MenuTagView;
import com.meogeodo.domain.Restaurant;
import com.meogeodo.domain.RestaurantFlag;
import com.meogeodo.domain.RestaurantFlagRepository;
import com.meogeodo.domain.RestaurantRepository;
import com.meogeodo.judgment.JudgmentEngine;
import com.meogeodo.judgment.MenuTags;
import com.meogeodo.judgment.UserHealthProfile;
import com.meogeodo.judgment.Verdict;
import com.meogeodo.search.SearchDtos.MenuView;
import com.meogeodo.search.SearchDtos.RestaurantDetail;
import com.meogeodo.search.SearchDtos.RestaurantSummary;
import com.meogeodo.search.SearchDtos.SearchResponse;
import com.meogeodo.search.SearchDtos.Seal;
import com.meogeodo.user.AppUser;
import com.meogeodo.user.AppUserRepository;
import com.meogeodo.user.UserService;
import com.meogeodo.vocabulary.VocabularyService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * GPS 반경 식당 검색 (SPEC 9.4, MVP 2번).
 *
 * <p>외부 API를 요청 경로에서 부르지 않는다. 인제스트로 적재해 둔 자체 DB를
 * 조회한다 — 공공 API가 느리거나 죽어도 서비스는 동작해야 한다.
 *
 * <p>로그인하지 않아도 목록은 볼 수 있다. 다만 판정은 사용자 프로필이 있어야
 * 가능하므로 {@code personalized=false} 로 알리고 낙관을 비운다. 판정을
 * 추측해서 내보내지 않는다.
 */
@Service
public class RestaurantSearchService {

  /** 한 번에 조회할 최대 반경. 너무 넓으면 사각 범위 후보가 폭증한다. */
  static final int MAX_RADIUS_M = 20_000;

  static final int DEFAULT_RADIUS_M = 2_000;

  /** 프론트 캔버스의 PER_PAGE (SPEC 9.4). */
  static final int DEFAULT_PAGE_SIZE = 3;

  static final int MAX_PAGE_SIZE = 50;

  private final RestaurantRepository restaurants;
  private final MenuQueryRepository menuQuery;
  private final RestaurantFlagRepository flags;
  private final AppUserRepository users;
  private final JudgmentEngine judgment;
  private final VocabularyService vocabulary;

  public RestaurantSearchService(
      RestaurantRepository restaurants,
      MenuQueryRepository menuQuery,
      RestaurantFlagRepository flags,
      AppUserRepository users,
      JudgmentEngine judgment,
      VocabularyService vocabulary) {
    this.restaurants = restaurants;
    this.menuQuery = menuQuery;
    this.flags = flags;
    this.users = users;
    this.judgment = judgment;
    this.vocabulary = vocabulary;
  }

  /** 좌표 기준 반경 검색. */
  @Transactional(readOnly = true)
  public SearchResponse search(
      Long userId, double lat, double lng, Integer radius, String query,
      List<String> requiredFlags, int page, int size) {

    int radiusM = clamp(radius == null ? DEFAULT_RADIUS_M : radius, 1, MAX_RADIUS_M);
    int pageSize = clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE);
    int pageIndex = Math.max(0, page);

    GeoBox box = GeoBox.around(lat, lng, radiusM);
    List<Restaurant> candidates =
        restaurants.findInBoundingBox(
            BigDecimal.valueOf(box.minLat()), BigDecimal.valueOf(box.maxLat()),
            BigDecimal.valueOf(box.minLng()), BigDecimal.valueOf(box.maxLng()));

    // 사각 범위는 원보다 넓다. 실제 반경으로 다시 거른다.
    record Hit(Restaurant restaurant, double meters) {}
    List<Hit> hits = new ArrayList<>();
    for (Restaurant r : candidates) {
      if (r.getLat() == null || r.getLng() == null) {
        continue;
      }
      double meters =
          GeoBox.distanceMeters(lat, lng, r.getLat().doubleValue(), r.getLng().doubleValue());
      if (meters <= radiusM) {
        hits.add(new Hit(r, meters));
      }
    }

    if (query != null && !query.isBlank()) {
      String needle = query.trim();
      hits = hits.stream().filter(h -> h.restaurant().getName().contains(needle)).toList();
    }

    Map<Long, Set<String>> flagsById =
        loadFlags(hits.stream().map(h -> h.restaurant().getId()).toList());
    if (requiredFlags != null && !requiredFlags.isEmpty()) {
      hits = hits.stream()
          .filter(h -> flagsById.getOrDefault(h.restaurant().getId(), Set.of())
              .containsAll(requiredFlags))
          .toList();
    }

    List<Hit> ordered =
        hits.stream().sorted(Comparator.comparingDouble(Hit::meters)).toList();
    int total = ordered.size();
    int from = Math.min(pageIndex * pageSize, total);
    int to = Math.min(from + pageSize, total);
    List<Hit> pageHits = ordered.subList(from, to);

    UserHealthProfile profile = profileOf(userId);
    boolean personalized = userId != null;
    Map<Long, List<MenuTags>> menusById =
        loadMenuTags(pageHits.stream().map(h -> h.restaurant().getId()).toList());

    List<RestaurantSummary> items = new ArrayList<>();
    for (Hit hit : pageHits) {
      Restaurant r = hit.restaurant();
      List<MenuTags> menus = menusById.getOrDefault(r.getId(), List.of());
      Verdict verdict = personalized ? judgment.judgeRestaurant(menus, profile) : null;
      items.add(new RestaurantSummary(
          r.getId(), r.getName(), meta(r, hit.meters()),
          r.getLat(), r.getLng(),
          (int) Math.round(hit.meters()), GeoBox.walkMinutes(hit.meters()),
          List.copyOf(flagsById.getOrDefault(r.getId(), Set.of())),
          seal(verdict),
          personalized ? summarize(menus, profile) : null,
          r.getFirstImage()));
    }

    return new SearchResponse(
        pageIndex, pageSize, total, personalized, items, UserService.DISCLAIMER);
  }

  /** 식당 상세. 좌표를 주면 거리도 함께 계산한다. */
  @Transactional(readOnly = true)
  public RestaurantDetail detail(Long userId, Long restaurantId, Double lat, Double lng) {
    Restaurant r = restaurants.findById(restaurantId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "식당을 찾을 수 없습니다"));

    Integer meters = null;
    Integer walk = null;
    if (lat != null && lng != null && r.getLat() != null && r.getLng() != null) {
      double d = GeoBox.distanceMeters(
          lat, lng, r.getLat().doubleValue(), r.getLng().doubleValue());
      meters = (int) Math.round(d);
      walk = GeoBox.walkMinutes(d);
    }

    UserHealthProfile profile = profileOf(userId);
    boolean personalized = userId != null;
    List<MenuRow> rows = loadMenuRows(List.of(restaurantId)).getOrDefault(restaurantId, List.of());

    List<MenuView> menus = new ArrayList<>();
    List<MenuTags> forRestaurant = new ArrayList<>();
    for (MenuRow row : rows) {
      MenuTags tags = row.toTags();
      // 미태깅 메뉴는 식당 판정에 넣지 않는다 (loadMenuTags 주석 참조).
      if (row.tagged()) {
        forRestaurant.add(tags);
      }
      menus.add(toMenuView(row, tags, profile, personalized));
    }

    Verdict verdict = personalized ? judgment.judgeRestaurant(forRestaurant, profile) : null;
    Set<String> flagSet = loadFlags(List.of(restaurantId)).getOrDefault(restaurantId, Set.of());

    return new RestaurantDetail(
        r.getId(), r.getName(), meta(r, meters == null ? -1 : meters),
        r.getLat(), r.getLng(), meters, walk,
        r.getTel(), r.getOpenTime(), r.getRestDate(), r.getParking(), r.getFirstImage(),
        List.copyOf(flagSet), seal(verdict), personalized, menus, UserService.DISCLAIMER);
  }

  // ── 내부 ──────────────────────────────────────────────────────────

  /** 메뉴 1건과 그 태그들. */
  private record MenuRow(
      Long id, String name, Integer price, boolean representative, boolean tagged,
      Set<String> cares, Set<String> mainAllergens, Set<String> traceAllergens,
      List<SearchDtos.MenuTagView> tagViews) {

    MenuTags toTags() {
      return new MenuTags(cares, mainAllergens, traceAllergens);
    }
  }

  private UserHealthProfile profileOf(Long userId) {
    if (userId == null) {
      return UserHealthProfile.empty();
    }
    AppUser user = users.findById(userId).orElse(null);
    if (user == null) {
      return UserHealthProfile.empty();
    }
    return new UserHealthProfile(user.careValues(), user.getAllergies());
  }

  private Map<Long, Set<String>> loadFlags(List<Long> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<Long, Set<String>> out = new LinkedHashMap<>();
    for (RestaurantFlag row : flags.findByRestaurantIdIn(ids)) {
      out.computeIfAbsent(row.getRestaurantId(), k -> new LinkedHashSet<>()).add(row.getFlag());
    }
    return out;
  }

  /**
   * 식당 판정에 쓸 메뉴 태그.
   *
   * <p><b>아직 태깅되지 않은 메뉴는 제외한다.</b> 태그가 없는 메뉴는 "걸리는
   * 것이 없는" 상태와 구분되지 않아, 그대로 판정에 넣으면 분석 전 식당이 전부
   * ○(먹어도 돼요)로 나온다. 지병이 있는 사용자에게 가장 위험한 오답이다.
   */
  private Map<Long, List<MenuTags>> loadMenuTags(List<Long> ids) {
    Map<Long, List<MenuRow>> rows = loadMenuRows(ids);
    Map<Long, List<MenuTags>> out = new LinkedHashMap<>();
    rows.forEach((id, list) -> out.put(id,
        list.stream().filter(MenuRow::tagged).map(MenuRow::toTags).toList()));
    return out;
  }

  private Map<Long, List<MenuRow>> loadMenuRows(List<Long> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    // (식당 -> 메뉴 -> 태그) 를 한 번의 쿼리로 읽고 자바에서 접는다.
    record Acc(String name, Integer price, boolean representative, boolean tagged,
        Set<String> cares, Set<String> main, Set<String> trace,
        List<SearchDtos.MenuTagView> views) {}

    Map<Long, Map<Long, Acc>> byRestaurant = new LinkedHashMap<>();
    for (MenuTagView v : menuQuery.findMenusWithTags(ids)) {
      Map<Long, Acc> menus =
          byRestaurant.computeIfAbsent(v.getRestaurantId(), k -> new LinkedHashMap<>());
      Acc acc = menus.computeIfAbsent(v.getMenuId(), k -> new Acc(
          v.getMenuName(), v.getPrice(), Boolean.TRUE.equals(v.getRepresentative()),
          Integer.valueOf(1).equals(v.getTagged()),
          new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(),
          new ArrayList<>()));

      if (v.getTagValue() == null) {
        continue;
      }
      boolean llm = "LLM".equals(v.getSource());
      acc.views().add(new SearchDtos.MenuTagView(
          v.getTagValue(), v.getAmount(), v.getSource(), llm));
      if ("CARE".equals(v.getTagType())) {
        acc.cares().add(v.getTagValue());
      } else if ("TRACE".equals(v.getAmount())) {
        acc.trace().add(v.getTagValue());
      } else {
        // amount 가 비어 있으면 주재료로 본다. 양념이라고 낮춰 잡는 것보다 안전하다.
        acc.main().add(v.getTagValue());
      }
    }

    Map<Long, List<MenuRow>> out = new LinkedHashMap<>();
    byRestaurant.forEach((restaurantId, menus) -> {
      List<MenuRow> list = new ArrayList<>();
      menus.forEach((menuId, a) -> list.add(new MenuRow(
          menuId, a.name(), a.price(), a.representative(), a.tagged(),
          a.cares(), a.main(), a.trace(), a.views())));
      out.put(restaurantId, list);
    });
    return out;
  }

  private MenuView toMenuView(
      MenuRow row, MenuTags tags, UserHealthProfile profile, boolean personalized) {

    if (!row.tagged()) {
      // 아직 분석되지 않았다. 판정을 추측해 내보내지 않는다 (SPEC 9.1).
      return new MenuView(row.id(), row.name(), row.price(), row.representative(),
          "PENDING", null, row.tagViews(), List.of(), List.of(), List.of(),
          "아직 분석되지 않은 메뉴입니다.", List.of());
    }
    if (!personalized) {
      return new MenuView(row.id(), row.name(), row.price(), row.representative(),
          "TAGGED", null, row.tagViews(), List.of(), List.of(), List.of(), null, List.of());
    }

    List<String> main = List.copyOf(judgment.hitMainAllergens(tags, profile));
    List<String> trace = List.copyOf(judgment.hitTraceAllergens(tags, profile));
    List<String> cares = List.copyOf(judgment.hitCares(tags, profile));
    Verdict verdict = judgment.judgeMenu(tags, profile);

    return new MenuView(
        row.id(), row.name(), row.price(), row.representative(),
        "TAGGED", seal(verdict), row.tagViews(),
        main, trace, cares,
        detailText(main, trace, cares),
        suggestedRequests(row.name(), main, trace, cares));
  }

  /** 사용자가 무엇을 어떻게 해야 하는지 한 문장으로 (SPEC 11.1). */
  private String detailText(List<String> main, List<String> trace, List<String> cares) {
    if (!main.isEmpty()) {
      return subject(String.join(" · ", main))
          + " 주재료로 들어갑니다. 다른 메뉴를 고르세요. 반드시 매장에 확인하세요.";
    }
    if (!trace.isEmpty()) {
      return subject(String.join(" · ", trace))
          + " 양념에 들어갈 수 있습니다. 빼 달라고 요청하고, 반드시 매장에 확인하세요.";
    }
    if (!cares.isEmpty()) {
      String note = vocabulary.careNotes().get(cares.get(0));
      return subject(String.join(" · ", cares)) + " 걸립니다."
          + (note == null ? "" : " " + note + ".");
    }
    return "주의 성분과 알레르기 재료가 모두 없습니다. 그대로 주문할 수 있습니다.";
  }

  /** 이 메뉴에 실제로 도움이 되는 요청 문구 (SPEC 2.6). */
  private List<String> suggestedRequests(
      String menuName, List<String> main, List<String> trace, List<String> cares) {
    Set<String> out = new LinkedHashSet<>();
    for (String allergen : trace) {
      out.add(josa(allergen) + " 빼 주세요");
    }
    for (String care : cares) {
      for (String phrase : vocabulary.requestsFor(care)) {
        if (fitsMenu(phrase, menuName)) {
          out.add(phrase);
        }
      }
    }
    return List.copyOf(out);
  }

  private static final java.util.regex.Pattern NOODLE = java.util.regex.Pattern.compile(
      "면|국수|라면|우동|냉면|짬뽕|짜장|자장|파스타|스파게티|칼국수|쌀국수|소바|모밀|메밀|라멘|수제비");
  private static final java.util.regex.Pattern RICE = java.util.regex.Pattern.compile(
      "밥|정식|백반|죽|리조또|필라프|오므라이스|카레");

  /**
   * "밥은 반만 주세요" 같은 문구가 그 음식에 말이 되는지.
   *
   * <p>정제 탄수화물에는 밥·면 문구가 둘 다 묶여 있어, 버거에 "밥은 반만 주세요"가
   * 제안됐다. 밥 문구는 밥 음식에만, 면 문구는 면 음식에만 붙인다. 빵·떡처럼
   * 둘 다 아니면 붙이지 않는다 — 엉뚱한 요청을 카드에 싣는 것보다 낫다.
   */
  static boolean fitsMenu(String phrase, String menuName) {
    String name = menuName == null ? "" : menuName;
    if (phrase.startsWith("밥")) {
      return RICE.matcher(name).find();
    }
    if (phrase.startsWith("면")) {
      return NOODLE.matcher(name).find();
    }
    return true;
  }

  /**
   * 받침에 따라 이/가를 고른다. 여러 개를 " · " 로 이은 경우 마지막 단어에 붙는다.
   *
   * <p>예전에는 "밀이(가)" 처럼 둘 다 적어 보냈다. 화면에서 그대로 읽히면 어색하다.
   * 한글이 아닌 글자로 끝나면 판단할 수 없으니 "이(가)" 로 둔다.
   */
  static String subject(String words) {
    if (words == null || words.isBlank()) {
      return "";
    }
    String w = words.trim();
    char last = w.charAt(w.length() - 1);
    if (last < 0xAC00 || last > 0xD7A3) {
      return w + "이(가)";
    }
    return w + (((last - 0xAC00) % 28 != 0) ? "이" : "가");
  }

  /** 받침에 따라 은/는을 고른다 (SPEC 5.4). */
  static String josa(String word) {
    if (word == null || word.isBlank()) {
      return "";
    }
    char last = word.trim().charAt(word.trim().length() - 1);
    if (last < 0xAC00 || last > 0xD7A3) {
      return word + "은";
    }
    return word + (((last - 0xAC00) % 28 != 0) ? "은" : "는");
  }

  private String summarize(List<MenuTags> menus, UserHealthProfile profile) {
    if (menus.isEmpty()) {
      return null;
    }
    // 식당 요약은 가장 안전한 메뉴를 대표로 삼는다 (SPEC 3.2).
    MenuTags best = menus.stream()
        .min(Comparator.comparingInt(m -> judgment.judgeMenu(m, profile).ordinal()))
        .orElse(menus.get(0));
    return judgment.summarize(best, profile);
  }

  private Seal seal(Verdict verdict) {
    return verdict == null
        ? null
        : new Seal(verdict.name(), verdict.symbol(), verdict.label());
  }

  private String meta(Restaurant r, double meters) {
    String area = r.getArea() == null ? "" : r.getArea();
    if (meters < 0) {
      return area;
    }
    String distance = meters < 1000
        ? "도보 " + GeoBox.walkMinutes(meters) + "분"
        : String.format("%.1fkm", meters / 1000);
    return area.isBlank() ? distance : area + " · " + distance;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
