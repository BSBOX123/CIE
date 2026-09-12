package com.meogeodo.ingest;

import com.meogeodo.ingest.KorServiceResponses.Envelope;
import com.meogeodo.ingest.KorServiceResponses.Item;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 한국관광공사 국문관광정보(KorService2) 클라이언트.
 *
 * <p><b>인증키 주의</b>: 공공데이터포털 키는 이미 URL 인코딩된 형태
 * ({@code ...Ew%3D%3D})로 발급된다. {@code UriComponentsBuilder} 가 다시
 * 인코딩하면 {@code %253D} 가 되어 인증이 실패하므로, 완성된 URI 문자열을
 * {@code URI.create} 로 그대로 넘긴다.
 */
@Component
public class KorServiceClient {

  private static final Logger log = LoggerFactory.getLogger(KorServiceClient.class);

  /** 음식점 contentTypeId. */
  public static final int CONTENT_TYPE_RESTAURANT = 39;

  private final RestClient restClient = RestClient.create();
  private final String baseUrl;
  private final String apiKey;

  public KorServiceClient(
      @Value("${meogeodo.public-data.kor-service-base-url}") String baseUrl,
      @Value("${meogeodo.public-data.api-key}") String apiKey) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
  }

  /** 전국/지역 음식점 목록 한 페이지. */
  public List<Item> areaBasedList(Integer areaCode, Integer sigunguCode, int page, int rows) {
    UriComponentsBuilder builder =
        common("areaBasedList2")
            .queryParam("contentTypeId", CONTENT_TYPE_RESTAURANT)
            .queryParam("numOfRows", rows)
            .queryParam("pageNo", page);
    if (areaCode != null) {
      builder.queryParam("areaCode", areaCode);
    }
    if (sigunguCode != null) {
      builder.queryParam("sigunguCode", sigunguCode);
    }
    return fetchItems(builder);
  }

  /**
   * 좌표 기준 반경 조회. 응답의 {@code dist} 가 미터 단위 거리를 그대로 준다
   * (SPEC 6.1) — 거리 계산을 직접 할 필요가 없다.
   */
  public List<Item> locationBasedList(double lat, double lng, int radiusMeters, int rows) {
    return fetchItems(
        common("locationBasedList2")
            .queryParam("contentTypeId", CONTENT_TYPE_RESTAURANT)
            .queryParam("mapX", lng)
            .queryParam("mapY", lat)
            .queryParam("radius", radiusMeters)
            .queryParam("arrange", "E") // 거리순
            .queryParam("numOfRows", rows)
            .queryParam("pageNo", 1));
  }

  /** 음식점 상세. 메뉴 원문({@code firstmenu}/{@code treatmenu})은 여기에만 있다. */
  public Item detailIntro(String contentId) {
    List<Item> items =
        fetchItems(
            common("detailIntro2")
                .queryParam("contentId", contentId)
                .queryParam("contentTypeId", CONTENT_TYPE_RESTAURANT));
    return items.isEmpty() ? null : items.get(0);
  }

  /** 전체 건수. 인제스트 페이지 수 계산에 쓴다. */
  public int totalCount(Integer areaCode) {
    UriComponentsBuilder builder =
        common("areaBasedList2")
            .queryParam("contentTypeId", CONTENT_TYPE_RESTAURANT)
            .queryParam("numOfRows", 1)
            .queryParam("pageNo", 1);
    if (areaCode != null) {
      builder.queryParam("areaCode", areaCode);
    }
    Envelope envelope = get(builder);
    if (envelope == null || envelope.response() == null || envelope.response().body() == null) {
      return 0;
    }
    Integer total = envelope.response().body().totalCount();
    return total == null ? 0 : total;
  }

  private UriComponentsBuilder common(String operation) {
    return UriComponentsBuilder.fromUriString(baseUrl + "/" + operation)
        .queryParam("MobileOS", "ETC")
        .queryParam("MobileApp", "meogeodo")
        .queryParam("_type", "json");
  }

  private List<Item> fetchItems(UriComponentsBuilder builder) {
    Envelope envelope = get(builder);
    if (envelope == null || envelope.response() == null) {
      return List.of();
    }
    if (envelope.response().header() != null && !envelope.response().header().isSuccess()) {
      log.warn("KorService2 오류 응답: {}", envelope.response().header().resultMsg());
      return List.of();
    }
    var body = envelope.response().body();
    if (body == null || body.items() == null) {
      return List.of();
    }
    return body.items().safeItems();
  }

  private Envelope get(UriComponentsBuilder builder) {
    // serviceKey는 이미 인코딩된 값이므로 마지막에 직접 붙인다.
    String url = builder.build(true).toUriString() + "&serviceKey=" + apiKey;
    try {
      return restClient.get().uri(URI.create(url)).retrieve().body(Envelope.class);
    } catch (Exception e) {
      log.warn("KorService2 호출 실패: {}", e.getMessage());
      return null;
    }
  }
}
