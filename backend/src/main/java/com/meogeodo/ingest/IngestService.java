package com.meogeodo.ingest;

import com.meogeodo.domain.Dish;
import com.meogeodo.domain.DishRepository;
import com.meogeodo.domain.Menu;
import com.meogeodo.domain.MenuRepository;
import com.meogeodo.domain.Restaurant;
import com.meogeodo.domain.RestaurantRepository;
import com.meogeodo.ingest.KorServiceResponses.Item;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KorService2 -> restaurant / menu / dish 적재 (SPEC 7.1 단계 1~4).
 *
 * <p>여기서는 태깅을 하지 않는다. 새로 만들어진 {@code dish} 는
 * {@code tagged_at} 이 비어 있어 태깅 배치의 큐에 자동으로 들어간다.
 */
@Service
public class IngestService {

  private static final Logger log = LoggerFactory.getLogger(IngestService.class);

  /** KorService2 한 페이지 최대 건수. */
  private static final int PAGE_SIZE = 100;

  private final KorServiceClient client;
  private final MenuTextParser parser;
  private final RestaurantRepository restaurants;
  private final MenuRepository menus;
  private final DishRepository dishes;

  public IngestService(
      KorServiceClient client,
      MenuTextParser parser,
      RestaurantRepository restaurants,
      MenuRepository menus,
      DishRepository dishes) {
    this.client = client;
    this.parser = parser;
    this.restaurants = restaurants;
    this.menus = menus;
    this.dishes = dishes;
  }

  /**
   * 전국(또는 지정 지역) 음식점을 수집한다.
   *
   * @param areaCode {@code null} 이면 전국
   * @param maxPages 0 이하면 전체
   */
  public IngestResult ingestArea(Integer areaCode, int maxPages) {
    int total = client.totalCount(areaCode);
    if (total == 0) {
      log.warn("수집 대상이 없습니다 (areaCode={})", areaCode);
      return IngestResult.empty();
    }
    int pages = (int) Math.ceil((double) total / PAGE_SIZE);
    if (maxPages > 0) {
      pages = Math.min(pages, maxPages);
    }
    log.info("인제스트 시작: 총 {}건, {}페이지 (areaCode={})", total, pages, areaCode);

    IngestResult result = IngestResult.empty();
    for (int page = 1; page <= pages; page++) {
      List<Item> items = client.areaBasedList(areaCode, null, page, PAGE_SIZE);
      for (Item item : items) {
        // 한 건이 실패해도 전체를 멈추지 않는다. 13,000여 건을 받는 작업이라
        // 예상 못 한 값 하나 때문에 한 시간짜리 적재가 통째로 날아가면 안 된다.
        // 건너뛴 건은 다음 실행에서 다시 시도된다(modifiedtime 이 저장되지 않으므로).
        try {
          result = result.plus(ingestOne(item));
        } catch (RuntimeException e) {
          log.warn("  건너뜀 contentid={} ({}): {}",
              item.contentid(), item.title(), e.getMessage());
          result = result.plus(new IngestResult(1, 0, 0, 0, 0, 0, 1));
        }
      }
      log.info("  {}/{} 페이지 완료 — 누적 {}", page, pages, result);
    }
    return result;
  }

  /**
   * 음식점 1건을 적재한다.
   *
   * <p>{@code modifiedtime} 이 그대로면 상세 조회를 건너뛴다. 상세는 건당 1회
   * 호출이라 전국 규모에서는 이 판단이 실행 시간을 좌우한다.
   */
  @Transactional
  public IngestResult ingestOne(Item item) {
    if (item.contentid() == null || item.title() == null) {
      return IngestResult.empty();
    }
    Restaurant existing = restaurants.findByContentId(item.contentid()).orElse(null);
    boolean isNew = existing == null;

    if (!isNew && Objects.equals(existing.getSourceModifiedAt(), item.modifiedtime())) {
      return new IngestResult(1, 0, 0, 1, 0, 0, 0);
    }

    Restaurant restaurant =
        isNew ? new Restaurant(item.contentid(), item.title()) : existing;
    applyListFields(restaurant, item);

    Item detail = client.detailIntro(item.contentid());
    String rawMenuText = applyDetailFields(restaurant, detail);
    restaurant.setSourceModifiedAt(item.modifiedtime());
    restaurant.setIngestedAt(OffsetDateTime.now());
    restaurants.save(restaurant);

    MenuOutcome outcome = syncMenus(restaurant, detail, rawMenuText);
    return new IngestResult(
        1, isNew ? 1 : 0, isNew ? 0 : 1, 0, outcome.menusCreated(), outcome.dishesCreated(), 0);
  }

  private void applyListFields(Restaurant restaurant, Item item) {
    restaurant.setName(item.title());
    restaurant.setAddr1(item.addr1());
    restaurant.setAddr2(item.addr2());
    restaurant.setArea(shortenAddress(item.addr1()));
    restaurant.setAreaCode(parseShort(item.areacode()));
    restaurant.setSigunguCode(parseShort(item.sigungucode()));
    // KorService2는 경도를 mapx, 위도를 mapy로 준다. 뒤바꾸면 지도가 통째로 어긋난다.
    restaurant.setLng(parseDecimal(item.mapx()));
    restaurant.setLat(parseDecimal(item.mapy()));
    restaurant.setFirstImage(blankToNull(item.firstimage()));
  }

  /** @return 메뉴 원문(firstmenu + treatmenu) */
  private String applyDetailFields(Restaurant restaurant, Item detail) {
    if (detail == null) {
      return null;
    }
    restaurant.setTel(blankToNull(detail.infocenterfood()));
    restaurant.setOpenTime(blankToNull(detail.opentimefood()));
    restaurant.setRestDate(blankToNull(detail.restdatefood()));
    restaurant.setParking(blankToNull(detail.parkingfood()));
    restaurant.setPacking(blankToNull(detail.packing()));
    restaurant.setReservation(blankToNull(detail.reservationfood()));

    String raw =
        java.util.stream.Stream.of(detail.firstmenu(), detail.treatmenu())
            .filter(s -> s != null && !s.isBlank())
            .reduce((a, b) -> a + "\n" + b)
            .orElse(null);
    restaurant.setRawMenuText(raw);
    return raw;
  }

  private record MenuOutcome(int menusCreated, int dishesCreated) {}

  /**
   * 메뉴를 다시 만든다.
   *
   * <p>기존 메뉴를 지우고 새로 넣는다. 메뉴 원문이 통째로 바뀌는 경우가 잦아
   * 차이를 계산하는 것보다 단순하고, {@code dish} 는 별도 테이블이라 태깅
   * 결과가 함께 지워지지 않는다.
   */
  private MenuOutcome syncMenus(Restaurant restaurant, Item detail, String rawMenuText) {
    if (detail == null || rawMenuText == null) {
      return new MenuOutcome(0, 0);
    }
    menus.deleteByRestaurantId(restaurant.getId());

    List<String> names = parser.parse(detail.firstmenu(), detail.treatmenu());
    int dishesCreated = 0;
    int created = 0;
    for (int i = 0; i < names.size(); i++) {
      String rawName = names.get(i);
      Menu menu = new Menu(restaurant, rawName, i == 0);

      String normalized = parser.normalize(rawName);
      if (!normalized.isBlank()) {
        Dish dish = dishes.findByNormalizedName(normalized).orElse(null);
        if (dish == null) {
          dish = dishes.save(new Dish(normalized));
          dishesCreated++;
        }
        menu.setDish(dish);
      }
      menus.save(menu);
      created++;
    }
    return new MenuOutcome(created, dishesCreated);
  }

  private static String shortenAddress(String addr) {
    if (addr == null || addr.isBlank()) {
      return null;
    }
    // "강원특별자치도 강릉시 초당동 ..." -> "강릉시 초당동"
    String[] parts = addr.trim().split("\\s+");
    if (parts.length >= 3) {
      return parts[1] + " " + parts[2];
    }
    return parts.length >= 2 ? parts[1] : parts[0];
  }

  private static Short parseShort(String value) {
    try {
      return value == null || value.isBlank() ? null : Short.valueOf(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static BigDecimal parseDecimal(String value) {
    try {
      return value == null || value.isBlank() ? null : new BigDecimal(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
