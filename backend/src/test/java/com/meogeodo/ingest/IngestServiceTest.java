package com.meogeodo.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meogeodo.domain.DishRepository;
import com.meogeodo.domain.MenuRepository;
import com.meogeodo.domain.RestaurantRepository;
import com.meogeodo.ingest.KorServiceResponses.Item;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인제스트 통합 테스트.
 *
 * <p>KorService2 응답은 실제로 수집한 값(2026-09-07)을 그대로 쓴다. 외부 호출만
 * 대역으로 바꾸고 파싱·정규화·적재는 진짜 코드를 통과시킨다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IngestServiceTest {

  @Autowired private IngestService ingestService;
  @Autowired private RestaurantRepository restaurants;
  @Autowired private MenuRepository menus;
  @Autowired private DishRepository dishes;

  @MockitoBean private KorServiceClient client;

  /** 실제 응답: 강릉 가람집옹심이. */
  private static Item listItem(String contentId, String title, String modified) {
    return new Item(
        contentId, "39", title,
        "강원특별자치도 강릉시 공항길30번길 16", "", "32", "1",
        "128.9393320379", "37.7611934162", "", "", modified, null,
        null, null, null, null, null, null, null, null);
  }

  private static Item detailItem(String contentId, String first, String treat) {
    return new Item(
        contentId, "39", null, null, null, null, null, null, null, null, null, null, null,
        first, treat, "0507-1313-3266", "10:30~20:00", "매주 화요일", "가능", "가능", "가능");
  }

  @Test
  @DisplayName("음식점과 메뉴와 음식이 함께 적재된다")
  void ingestsRestaurantMenusAndDishes() {
    when(client.detailIntro("2868839"))
        .thenReturn(detailItem("2868839", "감자전", "장칼옹심이, 장칼국수, 순옹심이 등"));

    var result = ingestService.ingestOne(listItem("2868839", "가람집옹심이", "20250904141526"));

    assertThat(result.created()).isEqualTo(1);
    assertThat(result.menusCreated()).isEqualTo(4);

    var saved = restaurants.findByContentId("2868839").orElseThrow();
    assertThat(saved.getName()).isEqualTo("가람집옹심이");
    assertThat(saved.getArea()).isEqualTo("강릉시 공항길30번길");
    assertThat(menus.findByRestaurantId(saved.getId())).hasSize(4);
  }

  @Test
  @DisplayName("mapx는 경도, mapy는 위도다 — 뒤바꾸면 지도가 통째로 어긋난다")
  void coordinateOrdering() {
    when(client.detailIntro(any())).thenReturn(null);
    ingestService.ingestOne(listItem("2868839", "가람집옹심이", "1"));

    var saved = restaurants.findByContentId("2868839").orElseThrow();
    // 강릉은 북위 37도, 동경 128도
    assertThat(saved.getLat().doubleValue()).isBetween(37.0, 38.0);
    assertThat(saved.getLng().doubleValue()).isBetween(128.0, 129.0);
  }

  @Test
  @DisplayName("대표메뉴는 firstmenu가 된다")
  void firstMenuIsRepresentative() {
    when(client.detailIntro(any()))
        .thenReturn(detailItem("A", "감자전", "장칼옹심이, 장칼국수"));
    ingestService.ingestOne(listItem("A", "가람집옹심이", "1"));

    var saved = restaurants.findByContentId("A").orElseThrow();
    var list = menus.findByRestaurantId(saved.getId());
    assertThat(list).filteredOn(com.meogeodo.domain.Menu::isRepresentative)
        .extracting(com.meogeodo.domain.Menu::getRawName)
        .containsExactly("감자전");
  }

  @Test
  @DisplayName("같은 음식은 식당이 달라도 하나의 dish를 공유한다 — 태깅 비용의 핵심")
  void dishesAreSharedAcrossRestaurants() {
    when(client.detailIntro("A")).thenReturn(detailItem("A", "김치찌개", "된장찌개"));
    when(client.detailIntro("B")).thenReturn(detailItem("B", "김치찌개", "제육볶음"));

    var first = ingestService.ingestOne(listItem("A", "식당가", "1"));
    var second = ingestService.ingestOne(listItem("B", "식당나", "1"));

    assertThat(first.dishesCreated()).isEqualTo(2);   // 김치찌개, 된장찌개
    assertThat(second.dishesCreated()).isEqualTo(1);  // 제육볶음만 신규
    assertThat(dishes.count()).isEqualTo(3);
  }

  @Test
  @DisplayName("크기만 다른 메뉴는 하나의 dish로 모인다")
  void sizeVariantsCollapse() {
    when(client.detailIntro("A"))
        .thenReturn(
            detailItem(
                "A",
                "갈매기스페셜(모둠회+대게)중",
                "갈매기스페셜(모둠회+대게)대, 갈매기스페셜(모둠회+대게)특대, 물회"));

    var result = ingestService.ingestOne(listItem("A", "갈매기횟집", "1"));

    assertThat(result.menusCreated()).isEqualTo(4);
    assertThat(result.dishesCreated()).isEqualTo(2); // 갈매기스페셜, 물회
  }

  @Test
  @DisplayName("modifiedtime이 같으면 상세를 다시 호출하지 않는다")
  void skipsUnchanged() {
    when(client.detailIntro("A")).thenReturn(detailItem("A", "감자전", null));
    ingestService.ingestOne(listItem("A", "가람집옹심이", "20250904141526"));

    var again = ingestService.ingestOne(listItem("A", "가람집옹심이", "20250904141526"));

    assertThat(again.skipped()).isEqualTo(1);
    assertThat(again.updated()).isZero();
    verify(client, org.mockito.Mockito.times(1)).detailIntro("A");
  }

  @Test
  @DisplayName("modifiedtime이 바뀌면 갱신한다")
  void updatesWhenChanged() {
    when(client.detailIntro("A")).thenReturn(detailItem("A", "감자전", null));
    ingestService.ingestOne(listItem("A", "가람집옹심이", "1"));

    var again = ingestService.ingestOne(listItem("A", "가람집옹심이 2호점", "2"));

    assertThat(again.updated()).isEqualTo(1);
    assertThat(restaurants.findByContentId("A").orElseThrow().getName())
        .isEqualTo("가람집옹심이 2호점");
  }

  @Test
  @DisplayName("상세가 없어도 식당은 저장된다 — 메뉴 없이도 지도에는 나와야 한다")
  void survivesMissingDetail() {
    when(client.detailIntro(any())).thenReturn(null);

    var result = ingestService.ingestOne(listItem("A", "가람집옹심이", "1"));

    assertThat(result.created()).isEqualTo(1);
    assertThat(result.menusCreated()).isZero();
    assertThat(restaurants.findByContentId("A")).isPresent();
  }

  @Test
  @DisplayName("contentid나 title이 없으면 무시한다")
  void ignoresIncompleteItems() {
    var noId = new Item(null, "39", "이름있음", null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null);

    assertThat(ingestService.ingestOne(noId).fetched()).isZero();
    verify(client, never()).detailIntro(any());
  }

  @Test
  @DisplayName("가격은 공공데이터에 없으므로 항상 비어 있다 (SPEC 12.1)")
  void priceIsAlwaysNull() {
    when(client.detailIntro(any())).thenReturn(detailItem("A", "순두부 백반", null));
    ingestService.ingestOne(listItem("A", "초당할머니순두부", "1"));

    var saved = restaurants.findByContentId("A").orElseThrow();
    assertThat(menus.findByRestaurantId(saved.getId()))
        .allSatisfy(m -> assertThat(m.getPrice()).isNull());
  }

  @Test
  @DisplayName("새 dish는 태깅 전 상태로 남아 태깅 큐에 들어간다")
  void newDishesAwaitTagging() {
    when(client.detailIntro(any())).thenReturn(detailItem("A", "김치찌개", null));
    ingestService.ingestOne(listItem("A", "식당", "1"));

    assertThat(dishes.findTop200ByTaggedAtIsNull())
        .extracting(com.meogeodo.domain.Dish::getNormalizedName)
        .contains("김치찌개");
  }

  @Test
  @DisplayName("페이지 단위 수집이 건수를 합산한다")
  void ingestAreaAggregates() {
    when(client.totalCount(eq(32))).thenReturn(2);
    when(client.areaBasedList(eq(32), any(), anyInt(), anyInt()))
        .thenReturn(List.of(listItem("A", "가", "1"), listItem("B", "나", "1")));
    when(client.detailIntro(any())).thenReturn(detailItem("x", "김치찌개", null));

    var result = ingestService.ingestArea(32, 1);

    assertThat(result.fetched()).isEqualTo(2);
    assertThat(result.created()).isEqualTo(2);
  }
}
