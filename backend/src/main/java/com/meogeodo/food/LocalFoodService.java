package com.meogeodo.food;

import com.meogeodo.domain.Dish;
import com.meogeodo.domain.DishRepository;
import com.meogeodo.domain.DishTag;
import com.meogeodo.domain.DishTagRepository;
import com.meogeodo.domain.LocalFood;
import com.meogeodo.domain.LocalFoodRepository;
import com.meogeodo.domain.LocalFoodTipRepository;
import com.meogeodo.food.FoodDtos.CareReason;
import com.meogeodo.food.FoodDtos.FoodDetail;
import com.meogeodo.food.FoodDtos.FoodItem;
import com.meogeodo.food.FoodDtos.FoodListResponse;
import com.meogeodo.food.FoodDtos.Tip;
import com.meogeodo.judgment.JudgmentEngine;
import com.meogeodo.judgment.MenuTags;
import com.meogeodo.judgment.UserHealthProfile;
import com.meogeodo.judgment.Verdict;
import com.meogeodo.search.GeoBox;
import com.meogeodo.search.RestaurantSearchService;
import com.meogeodo.search.SearchDtos.RestaurantSummary;
import com.meogeodo.search.SearchDtos.Seal;
import com.meogeodo.tour.MenuTextParser;
import com.meogeodo.tour.TourApi;
import com.meogeodo.tour.TourApi.Intro;
import com.meogeodo.tour.TourApi.Place;
import com.meogeodo.tour.TourApiException;
import com.meogeodo.user.UserService;
import com.meogeodo.vocabulary.VocabularyService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 지역 음식 (SPEC 9.3, 1.1.1).
 *
 * <p>사용자 좌표 → 지역 판정 → 그 지역 음식 + 판정 → 그 음식을 파는 근처 식당.
 *
 * <p><b>음식도 식당도 내 시도 것만.</b> 예전에는 대구에서도 부산 밀면이 목록에 뜨고,
 * 밀면을 누르면 경주 가게를 추천했다. "여기 음식"이라는 말과 어긋나 헷갈린다.
 *
 * <p><b>지역 판정</b>은 SPEC 1.1.1 대로 가장 가까운 식당의 시도 코드를 이어받는다.
 * 역지오코딩 API 가 따로 필요 없다. <b>파는 식당</b>은 저장하지 않고 요청 때마다
 * 관광공사에서 찾는다 (공모전 규정: 공사 데이터 적재 금지).
 */
@Service
public class LocalFoodService {

  private static final Logger log = LoggerFactory.getLogger(LocalFoodService.class);

  /** 지역 판정용 식당을 찾는 반경. 이 안에 식당이 없으면 지역을 모른다고 답한다. */
  static final int REGION_PROBE_RADIUS_M = 20_000;

  static final int MENU_PROBE_RADIUS_M = 5_000;

  /** 상호 검색 한 번에 받는 식당 수. 음식명이 상호에 들어간 곳은 전국에 수십 곳 수준이다. */
  static final int KEYWORD_ROWS = 100;

  static final int MAX_RESTAURANTS = 10;

  /** 현재 지역 음식을 보유한 권역 (경상권). 법정동 시도 코드. */
  static final Set<String> COVERED_REGIONS = Set.of("26", "27", "31", "47", "48");

  private static final Map<String, String> REGION_NAMES = Map.ofEntries(
      Map.entry("11", "서울"), Map.entry("12", "전남"), Map.entry("26", "부산"), Map.entry("27", "대구"),
      Map.entry("28", "인천"), Map.entry("29", "광주"), Map.entry("30", "대전"),
      Map.entry("31", "울산"), Map.entry("36", "세종"), Map.entry("41", "경기"),
      Map.entry("43", "충북"), Map.entry("44", "충남"), Map.entry("46", "전남"),
      Map.entry("47", "경북"), Map.entry("48", "경남"), Map.entry("50", "제주"),
      Map.entry("51", "강원"), Map.entry("52", "전북"), Map.entry("36110", "세종"));

  /**
   * 광주와 전남은 2026 년 "전남광주통합특별시"(코드 12)로 합쳐졌다. 사용자는 여전히
   * 광주·전남으로 부르므로 옛 광주광역시의 구는 "광주"로 보여 준다.
   */
  private static final Set<String> GWANGJU_DISTRICTS = Set.of("동구", "서구", "남구", "북구", "광산구");

  private final LocalFoodRepository foods;
  private final LocalFoodTipRepository tips;
  private final DishRepository dishes;
  private final DishTagRepository dishTags;
  private final TourApi tour;
  private final MenuTextParser parser;
  private final RestaurantSearchService search;
  private final JudgmentEngine judgment;
  private final VocabularyService vocabulary;

  /**
   * 이름에 음식명이 없는 가게를 찾으려고 메뉴까지 열어 보는 근처 식당 수.
   *
   * <p>한 곳에 detailIntro2 한 번이다. 개발계정은 일일 1,000회 한도라 30 으로 두었더니
   * 상세 몇십 번에 하루치가 바닥났다(2026-09-20). 운영계정 전환 전까지 0(끔)으로 둔다.
   */
  private final int menuProbeCount;

  public LocalFoodService(
      LocalFoodRepository foods,
      LocalFoodTipRepository tips,
      DishRepository dishes,
      DishTagRepository dishTags,
      TourApi tour,
      MenuTextParser parser,
      RestaurantSearchService search,
      JudgmentEngine judgment,
      VocabularyService vocabulary,
      @Value("${meogeodo.local-food.menu-probe-count:0}") int menuProbeCount) {
    this.foods = foods;
    this.tips = tips;
    this.dishes = dishes;
    this.dishTags = dishTags;
    this.tour = tour;
    this.parser = parser;
    this.search = search;
    this.judgment = judgment;
    this.vocabulary = vocabulary;
    this.menuProbeCount = Math.max(0, menuProbeCount);
  }

  /** 현재 위치의 지역 음식. */
  public FoodListResponse list(Long userId, double lat, double lng) {
    Region here = regionOf(lat, lng);
    boolean personalized = userId != null;
    if (here == null || here.code() == null || !COVERED_REGIONS.contains(here.code())) {
      // 보유하지 않은 지역은 섹션을 숨긴다 (SPEC 7.5). 지역 이름은 그래도 알려 준다.
      return new FoodListResponse(
          here == null ? null : here.name(), here == null ? null : here.district(),
          here == null ? null : here.code(), List.of(), personalized, UserService.DISCLAIMER);
    }

    List<LocalFood> mine = foods.findByRegionCodeOrderBySortOrderAsc(here.code());

    UserHealthProfile profile = search.profileOf(userId);
    Map<Long, Judged> judged = judge(mine.stream().map(LocalFood::getDishId).toList(), profile);

    List<FoodItem> items = new ArrayList<>();
    for (LocalFood f : mine) {
      Judged j = judged.getOrDefault(f.getDishId(), Judged.PENDING);
      boolean show = personalized && j.tagged();
      items.add(new FoodItem(
          f.getId(), f.getName(), f.getRegionLabel(),
          j.tagStatus(),
          show ? seal(judgment.judgeMenu(j.tags(), profile)) : null,
          show ? judgment.summarize(j.tags(), profile) : null));
    }
    return new FoodListResponse(
        here.name(), here.district(), here.code(), items, personalized, UserService.DISCLAIMER);
  }

  /**
   * 지역 음식 상세. 좌표를 주면 이 음식을 파는 근처 식당도 찾는다.
   *
   * <p>식당은 <b>사용자가 지금 있는 시도 안에서만</b> 찾는다. 그 시도에 파는 곳이 없으면
   * 멀리 다른 지역 가게를 대신 보여 주지 않고 비워 둔다.
   */
  public FoodDetail detail(Long userId, String foodId, Double lat, Double lng) {
    LocalFood food = foods.findById(foodId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "지역 음식을 찾을 수 없습니다"));

    UserHealthProfile profile = search.profileOf(userId);
    boolean personalized = userId != null;
    Judged j = judge(java.util.Collections.singletonList(food.getDishId()), profile)
        .getOrDefault(food.getDishId(), Judged.PENDING);
    boolean show = personalized && j.tagged();

    List<String> main = show ? List.copyOf(judgment.hitMainAllergens(j.tags(), profile)) : List.of();
    List<String> trace = show ? List.copyOf(judgment.hitTraceAllergens(j.tags(), profile)) : List.of();
    List<CareReason> cares = show
        ? judgment.hitCares(j.tags(), profile).stream()
            .map(c -> new CareReason(c, vocabulary.careNotes().get(c)))
            .toList()
        : List.of();

    List<Tip> tipList = tips.findByFoodIdOrderBySortOrderAsc(foodId).stream()
        .map(t -> new Tip(t.getPhrase(), false))
        .toList();

    List<RestaurantSummary> restaurants = List.of();
    boolean unavailable = false;
    if (lat != null && lng != null) {
      try {
        restaurants = restaurantsSelling(userId, food, lat, lng);
      } catch (TourApiException e) {
        // 음식 설명과 판정은 우리 데이터라 그대로 보여 줄 수 있다. 식당만 비운다.
        log.warn("지역 음식 {} 의 식당 조회 실패: {}", foodId, e.getMessage());
        unavailable = true;
      }
    }

    return new FoodDetail(
        food.getId(), food.getName(), food.getRegionLabel(),
        food.getRegionLabel() + " 지역 음식", food.getDescription(),
        j.tagStatus(),
        show ? seal(judgment.judgeMenu(j.tags(), profile)) : null,
        !main.isEmpty() || !trace.isEmpty(),
        allergenText(main, trace),
        cares, tipList, restaurants, unavailable, personalized, UserService.DISCLAIMER);
  }

  // ── 지역 판정 ─────────────────────────────────────────────────────

  /** 좌표의 지역. 가장 가까운 식당의 시도를 이어받는다 (SPEC 1.1.1). */
  record Region(String code, String name, String district) {}

  Region regionOf(double lat, double lng) {
    List<Place> nearest = tour.nearby(lat, lng, REGION_PROBE_RADIUS_M, 1, 1).places();
    if (nearest.isEmpty()) {
      return null;
    }
    Place p = nearest.get(0);
    String[] addr = p.addr1() == null ? new String[0] : p.addr1().trim().split("\\s+");
    String code = p.regionCode() != null ? p.regionCode() : codeFromAddress(addr);
    String district = addr.length > 1 ? addr[1] : null;
    String name = code == null ? (addr.length > 0 ? addr[0] : null) : REGION_NAMES.get(code);
    if ("12".equals(code) && GWANGJU_DISTRICTS.contains(district)) {
      name = "광주";
    }
    if (name == null && addr.length > 0) {
      name = addr[0];  // 모르는 코드면 주소의 시도 이름을 그대로
    }
    return new Region(code, name, district);
  }

  /** 시도 코드가 비어 오는 항목이 있다. 주소 첫 낱말로 보충한다. */
  private static String codeFromAddress(String[] addr) {
    if (addr.length == 0) {
      return null;
    }
    String first = addr[0];
    for (Map.Entry<String, String> e : REGION_NAMES.entrySet()) {
      if (first.startsWith(e.getValue())
          || (e.getValue().length() == 2 && first.startsWith(longForm(e.getValue())))) {
        return e.getKey();
      }
    }
    return null;
  }

  /** "경북" → "경상북" 처럼 도 이름의 긴 꼴 앞부분. */
  private static String longForm(String shortName) {
    return switch (shortName) {
      case "경북" -> "경상북";
      case "경남" -> "경상남";
      case "충북" -> "충청북";
      case "충남" -> "충청남";
      case "전북" -> "전북";
      case "전남" -> "전라남";
      default -> shortName;
    };
  }

  // ── 이 음식을 파는 곳 ─────────────────────────────────────────────

  /**
   * 이 음식을 파는 식당을 가까운 순으로.
   *
   * <p>두 갈래로 찾는다. ① 가게 이름에 음식명이 들어간 곳 — 내 시도 안 상호 검색.
   * ② 근처 식당 몇 곳의 메뉴를 열어 음식명이 있는 곳 — 이름만으로는 못 찾는 가게를
   * 보충한다.
   */
  private List<RestaurantSummary> restaurantsSelling(
      Long userId, LocalFood food, double lat, double lng) {

    // 메뉴 이름은 띄어쓰기를 뺀 정규화 이름으로 비교한다 ("아구 찜" = "아구찜").
    List<String> needles = food.keywordList().stream().map(parser::normalize).toList();
    Region here = regionOf(lat, lng);
    if (here == null || here.code() == null) {
      return List.of();
    }
    Map<String, Place> found = new LinkedHashMap<>();

    for (String keyword : food.keywordList()) {
      // API 가 시도로 거른다. 그래도 한 번 더 확인한다 — 코드가 비어 오는 항목이 있다.
      for (Place p : tour.keyword(keyword, here.code(), KEYWORD_ROWS)) {
        if (p.contentId() != null && p.lat() != null && p.lng() != null
            && here.code().equals(p.regionCode())) {
          found.putIfAbsent(p.contentId(), p);
        }
      }
    }

    // 근처 식당 메뉴 확인도 같은 시도만. 시도 경계 근처에서는 반경이 옆 시도로 넘어간다.
    List<Place> around = menuProbeCount == 0
        ? List.of()
        : tour.nearby(lat, lng, MENU_PROBE_RADIUS_M, 1, menuProbeCount).places().stream()
            .filter(p -> here.code().equals(p.regionCode()))
            .toList();
    Map<String, Optional<Intro>> intros = around.isEmpty()
        ? new LinkedHashMap<>()
        : tour.intros(around.stream().map(Place::contentId).toList());
    for (Place p : around) {
      Optional<Intro> intro = intros.get(p.contentId());
      if (intro != null && intro.isPresent() && sells(intro.get(), needles)) {
        found.putIfAbsent(p.contentId(), p);
      }
    }

    List<Place> nearest = found.values().stream()
        .map(p -> p.withDistance(GeoBox.distanceMeters(lat, lng, p.lat(), p.lng())))
        .sorted(Comparator.comparingDouble(Place::distanceMeters))
        .limit(MAX_RESTAURANTS)
        .toList();
    return search.summaries(userId, nearest, intros);
  }

  private boolean sells(Intro intro, List<String> needles) {
    if (!intro.hasMenu()) {
      return false;
    }
    for (String menu : parser.parse(intro.firstMenu(), intro.treatMenu())) {
      String normalized = parser.normalize(menu);
      for (String needle : needles) {
        if (normalized.contains(needle)) {
          return true;
        }
      }
    }
    return false;
  }

  // ── 판정 ──────────────────────────────────────────────────────────

  /** 음식 1건의 분석 상태와 태그. */
  record Judged(String tagStatus, MenuTags tags) {
    static final Judged PENDING = new Judged("PENDING", MenuTags.empty());

    boolean tagged() {
      return "TAGGED".equals(tagStatus);
    }
  }

  private Map<Long, Judged> judge(List<Long> dishIds, UserHealthProfile profile) {
    List<Long> ids = dishIds.stream().filter(id -> id != null).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    Set<Long> tagged = new LinkedHashSet<>();
    for (Dish d : dishes.findAllById(ids)) {
      if (d.isTagged()) {
        tagged.add(d.getId());
      }
    }
    Map<Long, List<DishTag>> byDish = new HashMap<>();
    if (!tagged.isEmpty()) {
      for (DishTag t : dishTags.findWithDishByDishIdIn(tagged)) {
        byDish.computeIfAbsent(t.getDish().getId(), k -> new ArrayList<>()).add(t);
      }
    }
    Map<Long, Judged> out = new HashMap<>();
    for (Long id : tagged) {
      out.put(id, new Judged("TAGGED", toTags(byDish.getOrDefault(id, List.of()))));
    }
    return out;
  }

  private static MenuTags toTags(List<DishTag> tags) {
    Set<String> cares = new LinkedHashSet<>();
    Set<String> main = new LinkedHashSet<>();
    Set<String> trace = new LinkedHashSet<>();
    for (DishTag t : tags) {
      if (t.getTagType() == DishTag.TagType.CARE) {
        cares.add(t.getTagValue());
      } else if (t.getAmount() == DishTag.Amount.TRACE) {
        trace.add(t.getTagValue());
      } else {
        // amount 가 비어 있으면 주재료로 본다. 양념이라고 낮춰 잡는 것보다 안전하다.
        main.add(t.getTagValue());
      }
    }
    return new MenuTags(cares, main, trace);
  }

  private static String allergenText(List<String> main, List<String> trace) {
    List<String> lines = new ArrayList<>();
    if (!main.isEmpty()) {
      lines.add(String.join(" · ", main) + " — 주재료라 뺄 수 없습니다. 다른 음식을 고르세요.");
    }
    if (!trace.isEmpty()) {
      lines.add(String.join(" · ", trace) + " — 양념에 들어갈 수 있습니다. 빼 달라고 요청하세요.");
    }
    return lines.isEmpty() ? null : String.join(" ", lines);
  }

  private static Seal seal(Verdict verdict) {
    return verdict == null ? null : new Seal(verdict.name(), verdict.symbol(), verdict.label());
  }
}
