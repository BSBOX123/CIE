package com.meogeodo.search;

import com.meogeodo.domain.Dish;
import com.meogeodo.domain.DishTag;
import com.meogeodo.domain.DishTagRepository;
import com.meogeodo.domain.RestaurantFlag;
import com.meogeodo.domain.RestaurantFlagRepository;
import com.meogeodo.judgment.JudgmentEngine;
import com.meogeodo.judgment.MenuTags;
import com.meogeodo.judgment.UserHealthProfile;
import com.meogeodo.judgment.Verdict;
import com.meogeodo.search.SearchDtos.MenuView;
import com.meogeodo.search.SearchDtos.RestaurantDetail;
import com.meogeodo.search.SearchDtos.RestaurantSummary;
import com.meogeodo.search.SearchDtos.SearchResponse;
import com.meogeodo.search.SearchDtos.Seal;
import com.meogeodo.tour.MenuTextParser;
import com.meogeodo.tour.TourApi;
import com.meogeodo.tour.TourApi.Intro;
import com.meogeodo.tour.TourApi.NearbyPage;
import com.meogeodo.tour.TourApi.Place;
import com.meogeodo.user.AppUserRepository;
import com.meogeodo.user.UserService;
import com.meogeodo.vocabulary.VocabularyService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * GPS 반경 식당 검색 (SPEC 9.4, MVP 2번).
 *
 * <p><b>식당·메뉴는 요청마다 관광공사 API 에서 실시간으로 받는다.</b> 공모전 규정상
 * 공사 데이터를 DB 에 적재해 서빙할 수 없다. DB 에서 읽는 것은 우리가 만든 것뿐이다
 * — 음식 이름별 분석({@link DishDictionary}), 제보로 도출한 식당 속성, 사용자 프로필.
 *
 * <p>관광공사 호출 동안 DB 연결을 잡아 두지 않으려고 메서드 전체를 트랜잭션으로
 * 묶지 않는다. 호출 한 번이 수백 ms 라, 묶으면 동시 검색 몇 건에 연결 풀이 마른다.
 *
 * <p>로그인하지 않아도 목록은 볼 수 있다. 다만 판정은 사용자 프로필이 있어야
 * 가능하므로 {@code personalized=false} 로 알리고 낙관을 비운다. 판정을
 * 추측해서 내보내지 않는다.
 */
@Service
public class RestaurantSearchService {

  /** 한 번에 조회할 최대 반경. locationBasedList2 의 상한이기도 하다. */
  static final int MAX_RADIUS_M = 20_000;

  static final int DEFAULT_RADIUS_M = 2_000;

  /** 프론트 캔버스의 PER_PAGE (SPEC 9.4). */
  static final int DEFAULT_PAGE_SIZE = 3;

  static final int MAX_PAGE_SIZE = 50;

  /**
   * 이름·속성으로 거를 때 한 번에 훑는 식당 수.
   *
   * <p>API 에는 "반경 + 이름" 검색이 없어 가까운 순으로 받아 와서 거른다. 도심에서
   * 반경을 넓게 잡으면 이보다 많을 수 있는데, 그때는 가까운 곳부터 이만큼만 본다.
   */
  static final int FILTER_SCAN_ROWS = 500;

  static final String INTRO_FAILED = "메뉴 정보를 잠시 불러오지 못했습니다";

  static final String NO_MENU = "메뉴 정보가 없습니다";

  private final TourApi tour;
  private final MenuTextParser parser;
  private final DishDictionary dictionary;
  private final DishTagRepository dishTags;
  private final RestaurantFlagRepository flags;
  private final AppUserRepository users;
  private final JudgmentEngine judgment;
  private final VocabularyService vocabulary;
  private final TransactionTemplate readTx;

  public RestaurantSearchService(
      TourApi tour,
      MenuTextParser parser,
      DishDictionary dictionary,
      DishTagRepository dishTags,
      RestaurantFlagRepository flags,
      AppUserRepository users,
      JudgmentEngine judgment,
      VocabularyService vocabulary,
      PlatformTransactionManager txManager) {
    this.tour = tour;
    this.parser = parser;
    this.dictionary = dictionary;
    this.dishTags = dishTags;
    this.flags = flags;
    this.users = users;
    this.judgment = judgment;
    this.vocabulary = vocabulary;
    this.readTx = new TransactionTemplate(txManager);
    this.readTx.setReadOnly(true);
  }

  /** 좌표 기준 반경 검색. */
  public SearchResponse search(
      Long userId, double lat, double lng, Integer radius, String query,
      List<String> requiredFlags, int page, int size) {

    int radiusM = clamp(radius == null ? DEFAULT_RADIUS_M : radius, 1, MAX_RADIUS_M);
    int pageSize = clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE);
    int pageIndex = Math.max(0, page);
    boolean byName = query != null && !query.isBlank();
    boolean byFlag = requiredFlags != null && !requiredFlags.isEmpty();

    List<Place> pagePlaces;
    int total;
    Map<Long, Set<String>> flagsById;

    if (!byName && !byFlag) {
      // 거를 것이 없으면 API 의 페이지를 그대로 쓴다. 필요한 만큼만 받는다.
      NearbyPage found = tour.nearby(lat, lng, radiusM, pageIndex + 1, pageSize);
      pagePlaces = withId(found.places());
      total = found.totalCount();
      flagsById = loadFlags(ids(pagePlaces));
    } else {
      List<Place> hits = withId(tour.nearby(lat, lng, radiusM, 1, FILTER_SCAN_ROWS).places());
      if (byName) {
        String needle = query.trim();
        hits = hits.stream().filter(p -> p.title() != null && p.title().contains(needle)).toList();
      }
      flagsById = loadFlags(ids(hits));
      if (byFlag) {
        Map<Long, Set<String>> known = flagsById;
        hits = hits.stream()
            .filter(p -> known.getOrDefault(idOf(p), Set.of()).containsAll(requiredFlags))
            .toList();
      }
      total = hits.size();
      int from = Math.min(pageIndex * pageSize, total);
      pagePlaces = hits.subList(from, Math.min(from + pageSize, total));
    }

    List<Place> located = pagePlaces.stream()
        .map(p -> p.distanceMeters() == null ? p.withDistance(distance(lat, lng, p)) : p)
        .toList();
    List<RestaurantSummary> items = summaries(userId, located, flagsById, Map.of());
    boolean personalized = userId != null;

    return new SearchResponse(
        pageIndex, pageSize, total, personalized, items, UserService.DISCLAIMER);
  }

  /**
   * 식당 목록 한 장. 검색과 지역 음식의 "파는 곳"이 같은 모양으로 나가도록 공유한다.
   *
   * @param places 거리({@code distanceMeters})가 채워진 식당
   * @param knownIntros 이미 받아 둔 소개 정보. 없는 것만 새로 부른다
   */
  public List<RestaurantSummary> summaries(
      Long userId, List<Place> places, Map<String, Optional<Intro>> knownIntros) {
    return summaries(userId, withId(places), loadFlags(ids(withId(places))), knownIntros);
  }

  private List<RestaurantSummary> summaries(
      Long userId, List<Place> places, Map<Long, Set<String>> flagsById,
      Map<String, Optional<Intro>> knownIntros) {

    UserHealthProfile profile = profileOf(userId);
    boolean personalized = userId != null;

    // 식당별 메뉴를 병렬로 받는다. 못 받은 식당은 결과에서 빠진다.
    Map<String, Optional<Intro>> intros = new LinkedHashMap<>();
    List<String> missing = new ArrayList<>();
    for (Place p : places) {
      if (knownIntros.containsKey(p.contentId())) {
        intros.put(p.contentId(), knownIntros.get(p.contentId()));
      } else {
        missing.add(p.contentId());
      }
    }
    if (!missing.isEmpty()) {
      intros.putAll(tour.intros(missing));
    }
    Map<String, List<MenuRow>> menusById = loadMenuRows(intros);

    List<RestaurantSummary> items = new ArrayList<>();
    for (Place p : places) {
      Long id = idOf(p);
      double meters = p.distanceMeters() == null ? 0 : p.distanceMeters();
      List<String> flagList = List.copyOf(flagsById.getOrDefault(id, Set.of()));

      Seal seal = null;
      String summary;
      if (!intros.containsKey(p.contentId())) {
        summary = INTRO_FAILED;
      } else if (intros.get(p.contentId()).map(Intro::hasMenu).orElse(false)) {
        // 미태깅 메뉴는 식당 판정에 넣지 않는다 (taggedOnly 주석 참조).
        List<MenuTags> menus = taggedOnly(menusById.getOrDefault(p.contentId(), List.of()));
        seal = personalized ? seal(judgment.judgeRestaurant(menus, profile)) : null;
        summary = personalized ? summarize(menus, profile) : null;
      } else {
        summary = NO_MENU;
      }

      items.add(new RestaurantSummary(
          id, p.title(), meta(p.addr1(), meters),
          decimal(p.lat()), decimal(p.lng()),
          (int) Math.round(meters), GeoBox.walkMinutes(meters),
          flagList, seal, summary, p.firstImage()));
    }
    return items;
  }

  /**
   * 식당 상세. 좌표를 주면 거리도 함께 계산한다.
   *
   * <p>메뉴를 불러오지 못하면 빈 메뉴로 답하지 않고 실패로 알린다({@code 503}).
   * 빈 메뉴는 "이 식당엔 메뉴 정보가 없다"로 읽힌다.
   */
  public RestaurantDetail detail(Long userId, Long restaurantId, Double lat, Double lng) {
    String contentId = String.valueOf(restaurantId);
    Place p = tour.place(contentId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "식당을 찾을 수 없습니다"));
    Optional<Intro> intro = tour.intro(contentId);

    Integer meters = null;
    Integer walk = null;
    if (lat != null && lng != null && p.lat() != null && p.lng() != null) {
      double d = distance(lat, lng, p);
      meters = (int) Math.round(d);
      walk = GeoBox.walkMinutes(d);
    }

    UserHealthProfile profile = profileOf(userId);
    boolean personalized = userId != null;
    List<MenuRow> rows =
        loadMenuRows(Map.of(contentId, intro)).getOrDefault(contentId, List.of());

    List<MenuView> menus = new ArrayList<>();
    for (MenuRow row : rows) {
      menus.add(toMenuView(row, row.toTags(), profile, personalized));
    }
    Verdict verdict = personalized ? judgment.judgeRestaurant(taggedOnly(rows), profile) : null;
    Set<String> flagSet = loadFlags(List.of(restaurantId)).getOrDefault(restaurantId, Set.of());
    Intro info = intro.orElse(null);

    return new RestaurantDetail(
        restaurantId, p.title(), meta(p.addr1(), meters == null ? -1 : meters),
        decimal(p.lat()), decimal(p.lng()), meters, walk,
        info == null ? null : info.tel(),
        info == null ? null : info.openTime(),
        info == null ? null : info.restDate(),
        info == null ? null : info.parking(),
        p.firstImage(),
        List.copyOf(flagSet), seal(verdict), personalized, menus, UserService.DISCLAIMER);
  }

  // ── 내부 ──────────────────────────────────────────────────────────

  /**
   * 메뉴 1건과 그 태그들.
   *
   * @param id 메뉴 원문 이름에서 만든 값. 메뉴는 저장하지 않으므로 DB id 가 없다.
   *     같은 식당의 같은 메뉴는 언제 불러도 같은 값이라 카드 생성 때 다시 찾을 수 있다
   */
  record MenuRow(
      Long id, String name, Integer price, boolean representative, boolean tagged,
      Set<String> cares, Set<String> mainAllergens, Set<String> traceAllergens,
      List<SearchDtos.MenuTagView> tagViews) {

    MenuTags toTags() {
      return new MenuTags(cares, mainAllergens, traceAllergens);
    }
  }

  /** 메뉴 원문 이름 → 메뉴 id. 이름이 같으면 항상 같은 값이다. */
  public static long menuId(String rawName) {
    return Integer.toUnsignedLong(rawName.hashCode());
  }

  /** 판정에 쓸 사용자 정보. 비로그인이면 빈 프로필. */
  public UserHealthProfile profileOf(Long userId) {
    if (userId == null) {
      return UserHealthProfile.empty();
    }
    // 관심사·알레르기는 지연 로딩이라 이 안에서 꺼내 복사해 둔다.
    return readTx.execute(status -> users.findById(userId)
        .map(u -> new UserHealthProfile(u.careValues(), u.getAllergies()))
        .orElse(UserHealthProfile.empty()));
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
  private static List<MenuTags> taggedOnly(List<MenuRow> rows) {
    return rows.stream().filter(MenuRow::tagged).map(MenuRow::toTags).toList();
  }

  /**
   * 관광공사 메뉴 원문을 메뉴 목록으로 풀고, 음식 사전에서 분석 결과를 붙인다.
   *
   * <p>여러 식당의 메뉴를 모아 사전 조회와 태그 조회를 각각 한 번에 한다.
   */
  private Map<String, List<MenuRow>> loadMenuRows(Map<String, Optional<Intro>> intros) {
    record Parsed(String rawName, String normalized, boolean representative) {}

    Map<String, List<Parsed>> parsedById = new LinkedHashMap<>();
    Set<String> names = new LinkedHashSet<>();
    intros.forEach((contentId, intro) -> {
      if (intro.isEmpty() || !intro.get().hasMenu()) {
        return;
      }
      List<String> raw = parser.parse(intro.get().firstMenu(), intro.get().treatMenu());
      List<Parsed> list = new ArrayList<>();
      for (int i = 0; i < raw.size(); i++) {
        String normalized = parser.normalize(raw.get(i));
        list.add(new Parsed(raw.get(i), normalized, i == 0));
        names.add(normalized);
      }
      parsedById.put(contentId, list);
    });
    if (parsedById.isEmpty()) {
      return Map.of();
    }

    Map<String, Dish> dishes = dictionary.resolve(names);
    List<Long> taggedIds = dishes.values().stream()
        .filter(Dish::isTagged).map(Dish::getId).distinct().toList();
    Map<Long, List<DishTag>> tagsByDish = new HashMap<>();
    if (!taggedIds.isEmpty()) {
      for (DishTag t : dishTags.findWithDishByDishIdIn(taggedIds)) {
        tagsByDish.computeIfAbsent(t.getDish().getId(), k -> new ArrayList<>()).add(t);
      }
    }

    Map<String, List<MenuRow>> out = new LinkedHashMap<>();
    parsedById.forEach((contentId, list) -> {
      List<MenuRow> rows = new ArrayList<>();
      for (Parsed m : list) {
        Dish dish = dishes.get(m.normalized());
        boolean tagged = dish != null && dish.isTagged();
        rows.add(toRow(m.rawName(), m.representative(), tagged,
            tagged ? tagsByDish.getOrDefault(dish.getId(), List.of()) : List.of()));
      }
      out.put(contentId, rows);
    });
    return out;
  }

  private static MenuRow toRow(
      String rawName, boolean representative, boolean tagged, List<DishTag> tags) {
    Set<String> cares = new LinkedHashSet<>();
    Set<String> main = new LinkedHashSet<>();
    Set<String> trace = new LinkedHashSet<>();
    List<SearchDtos.MenuTagView> views = new ArrayList<>();
    for (DishTag t : tags) {
      String value = t.getTagValue();
      String amount = t.getAmount() == null ? null : t.getAmount().name();
      String source = t.getSource().name();
      views.add(new SearchDtos.MenuTagView(value, amount, source, "LLM".equals(source)));
      if (t.getTagType() == DishTag.TagType.CARE) {
        cares.add(value);
      } else if (t.getAmount() == DishTag.Amount.TRACE) {
        trace.add(value);
      } else {
        // amount 가 비어 있으면 주재료로 본다. 양념이라고 낮춰 잡는 것보다 안전하다.
        main.add(value);
      }
    }
    // 가격은 어떤 공공 API 에도 없다 (SPEC 12.1).
    return new MenuRow(menuId(rawName), rawName, null, representative, tagged,
        cares, main, trace, views);
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

  private static String meta(String addr1, double meters) {
    String area = shortenAddress(addr1);
    if (meters < 0) {
      return area;
    }
    String distance = meters < 1000
        ? "도보 " + GeoBox.walkMinutes(meters) + "분"
        : String.format("%.1fkm", meters / 1000);
    return area.isBlank() ? distance : area + " · " + distance;
  }

  /** "강원특별자치도 강릉시 초당동 ..." → "강릉시 초당동". */
  static String shortenAddress(String addr) {
    if (addr == null || addr.isBlank()) {
      return "";
    }
    String[] parts = addr.trim().split("\\s+");
    if (parts.length >= 3) {
      return parts[1] + " " + parts[2];
    }
    return parts.length >= 2 ? parts[1] : parts[0];
  }

  /** contentId 가 숫자가 아닌 항목은 버린다. 식당 id 로 쓸 수 없다. */
  private static List<Place> withId(List<Place> places) {
    return places.stream()
        .filter(p -> p.contentId() != null && p.contentId().matches("\\d{1,18}"))
        .toList();
  }

  private static Long idOf(Place p) {
    return Long.valueOf(p.contentId());
  }

  private static List<Long> ids(List<Place> places) {
    return places.stream().map(RestaurantSearchService::idOf).toList();
  }

  private static double distance(double lat, double lng, Place p) {
    if (p.lat() == null || p.lng() == null) {
      return 0;
    }
    return GeoBox.distanceMeters(lat, lng, p.lat(), p.lng());
  }

  private static BigDecimal decimal(Double value) {
    return value == null ? null : BigDecimal.valueOf(value);
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
