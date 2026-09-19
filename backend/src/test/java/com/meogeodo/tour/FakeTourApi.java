package com.meogeodo.tour;

import com.meogeodo.search.GeoBox;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 테스트용 관광공사 API. 실제 API 를 부르지 않고 메모리에 넣어 둔 식당을 돌려준다.
 *
 * <p>모든 통합 테스트에서 실제 클라이언트 대신 주입된다. 테스트마다 {@link #reset()} 으로
 * 비울 것 — 스프링 컨텍스트가 테스트 클래스 사이에 공유된다.
 */
@Component
@Primary
public class FakeTourApi implements TourApi {

  private final Map<String, Place> places = new ConcurrentHashMap<>();
  private final Map<String, Intro> intros = new ConcurrentHashMap<>();
  private final Set<String> failingIntros = ConcurrentHashMap.newKeySet();
  private final AtomicLong sequence = new AtomicLong(2_000_000);
  private volatile boolean down;

  public void reset() {
    places.clear();
    intros.clear();
    failingIntros.clear();
    down = false;
  }

  /** 식당을 넣는다. @return contentId */
  public String add(String title, double lat, double lng, String addr1) {
    return add(title, lat, lng, addr1, "51");  // 강원
  }

  /** 시도 코드({@code lDongRegnCd})까지 지정해 넣는다. @return contentId */
  public String add(String title, double lat, double lng, String addr1, String regionCode) {
    String contentId = String.valueOf(sequence.incrementAndGet());
    places.put(contentId,
        new Place(contentId, title, addr1, lat, lng, null, null, regionCode, null));
    return contentId;
  }

  /** 메뉴 원문. 실제 API 처럼 자유 텍스트다 (예: "물회, 회덮밥 등"). */
  public void menu(String contentId, String firstMenu, String treatMenu) {
    intros.put(contentId,
        new Intro(firstMenu, treatMenu, "033-000-0000", "10:00~21:00", "월요일", "가능"));
  }

  /** 이 식당의 메뉴 조회만 실패하게 한다 (초당 한도 초과 등). */
  public void failIntro(String contentId) {
    failingIntros.add(contentId);
  }

  /** API 전체가 죽은 상황. */
  public void down(boolean down) {
    this.down = down;
  }

  @Override
  public NearbyPage nearby(double lat, double lng, int radiusMeters, int pageNo, int rows) {
    guard();
    List<Place> within = places.values().stream()
        .map(p -> p.withDistance(GeoBox.distanceMeters(lat, lng, p.lat(), p.lng())))
        .filter(p -> p.distanceMeters() <= radiusMeters)
        .sorted(Comparator.comparingDouble(Place::distanceMeters))
        .toList();
    int from = Math.min((pageNo - 1) * rows, within.size());
    int to = Math.min(from + rows, within.size());
    return new NearbyPage(within.subList(from, to), within.size());
  }

  @Override
  public List<Place> keyword(String keyword, String regionCode, int rows) {
    guard();
    return places.values().stream()
        .filter(p -> p.title().contains(keyword))
        .filter(p -> regionCode == null || regionCode.equals(p.regionCode()))
        .sorted(Comparator.comparing(Place::contentId))
        .limit(rows)
        .toList();
  }

  @Override
  public Optional<Place> place(String contentId) {
    guard();
    return Optional.ofNullable(places.get(contentId));
  }

  @Override
  public Optional<Intro> intro(String contentId) {
    guard();
    if (failingIntros.contains(contentId)) {
      throw new TourApiException("detailIntro2 초당 한도 초과");
    }
    if (!places.containsKey(contentId)) {
      return Optional.empty();
    }
    // 메뉴를 안 넣은 식당은 실제 API 처럼 메뉴 칸이 빈 소개 정보를 준다.
    return Optional.of(intros.getOrDefault(contentId,
        new Intro(null, null, null, null, null, null)));
  }

  @Override
  public Map<String, Optional<Intro>> intros(Collection<String> contentIds) {
    return each(contentIds, this::intro);
  }

  @Override
  public Map<String, Optional<Place>> places(Collection<String> contentIds) {
    return each(contentIds, this::place);
  }

  private static <T> Map<String, Optional<T>> each(
      Collection<String> ids, Function<String, Optional<T>> one) {
    Map<String, Optional<T>> out = new LinkedHashMap<>();
    for (String id : ids) {
      try {
        out.put(id, one.apply(id));
      } catch (TourApiException e) {
        // 실제 클라이언트와 같다: 실패한 건은 빠진다.
      }
    }
    return out;
  }

  private void guard() {
    if (down) {
      throw new TourApiException("관광공사 API 장애");
    }
  }
}
