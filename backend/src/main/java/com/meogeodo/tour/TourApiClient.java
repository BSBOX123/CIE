package com.meogeodo.tour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 관광공사 KorService2 실시간 클라이언트.
 *
 * <h2>초당 요청 한도</h2>
 *
 * 공공데이터포털은 초당 요청 수를 제한한다. 실측으로 동시 5건은 안정적이었고
 * 동시 10건에서 {@code LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR}
 * (코드 23, HTTP 429) 가 났다. 그래서
 *
 * <ul>
 *   <li>서버 전체에서 동시에 나가는 호출을 {@code max-concurrent} 개로 묶는다 —
 *       사용자 여럿이 동시에 검색해도 한도를 넘지 않게
 *   <li>코드 23 은 잠깐 쉬었다 다시 시도한다
 *   <li>코드 22(일일 한도 초과)·인증 오류는 다시 해도 소용없으니 바로 실패한다
 * </ul>
 *
 * <h2>인증키</h2>
 *
 * 공공데이터포털 키는 이미 URL 인코딩된 형태로 발급된다. 다시 인코딩하면
 * {@code %253D} 가 되어 인증이 깨지므로, 완성된 URL 문자열에 그대로 붙인다.
 */
@Component
public class TourApiClient implements TourApi {

  private static final Logger log = LoggerFactory.getLogger(TourApiClient.class);

  /** 음식점 contentTypeId. */
  static final int CONTENT_TYPE_RESTAURANT = 39;

  /** 초당 한도 초과. 잠깐 쉬면 풀린다. */
  private static final String RATE_PER_SECOND = "23";

  /** 일일 한도 초과. 오늘은 더 해도 안 된다. */
  private static final String RATE_PER_DAY = "22";

  private static final int MAX_ATTEMPTS = 3;

  private final String baseUrl;
  private final String apiKey;
  private final ObjectMapper json;
  private final Semaphore permits;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  // 대부분 네트워크 대기라 가상 스레드로 충분하다. 실제 동시 호출 수는 permits 가 정한다.
  private final ExecutorService fanOut = Executors.newVirtualThreadPerTaskExecutor();

  public TourApiClient(
      @Value("${meogeodo.public-data.kor-service-base-url}") String baseUrl,
      @Value("${meogeodo.public-data.api-key}") String apiKey,
      @Value("${meogeodo.public-data.max-concurrent:5}") int maxConcurrent,
      ObjectMapper json) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.json = json;
    this.permits = new Semaphore(Math.max(1, maxConcurrent), true);
  }

  @Override
  public NearbyPage nearby(double lat, double lng, int radiusMeters, int pageNo, int rows) {
    JsonNode body = call("locationBasedList2",
        "contentTypeId=" + CONTENT_TYPE_RESTAURANT
            + "&mapX=" + lng + "&mapY=" + lat
            + "&radius=" + radiusMeters
            + "&arrange=E" // 거리순. dist 를 미터 단위로 함께 준다
            + "&numOfRows=" + rows + "&pageNo=" + pageNo);
    List<Place> places = items(body).stream().map(TourApiClient::toPlace).toList();
    return new NearbyPage(places, body.path("totalCount").asInt(places.size()));
  }

  @Override
  public List<Place> keyword(String keyword, String regionCode, int rows) {
    if (keyword == null || keyword.isBlank()) {
      return List.of();
    }
    String region = regionCode != null && regionCode.matches("\\d{1,5}")
        ? "&lDongRegnCd=" + regionCode
        : "";
    JsonNode body = call("searchKeyword2",
        "contentTypeId=" + CONTENT_TYPE_RESTAURANT
            + "&keyword=" + URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8)
            + region
            + "&arrange=A&numOfRows=" + rows + "&pageNo=1");
    return items(body).stream().map(TourApiClient::toPlace).toList();
  }

  @Override
  public Optional<Place> place(String contentId) {
    if (!isContentId(contentId)) {
      return Optional.empty();
    }
    JsonNode body = call("detailCommon2", "contentId=" + contentId);
    return items(body).stream().findFirst().map(TourApiClient::toPlace);
  }

  @Override
  public Optional<Intro> intro(String contentId) {
    if (!isContentId(contentId)) {
      return Optional.empty();
    }
    JsonNode body = call("detailIntro2",
        "contentId=" + contentId + "&contentTypeId=" + CONTENT_TYPE_RESTAURANT);
    return items(body).stream().findFirst().map(TourApiClient::toIntro);
  }

  @Override
  public Map<String, Optional<Intro>> intros(Collection<String> contentIds) {
    return fanOut(contentIds, this::intro);
  }

  @Override
  public Map<String, Optional<Place>> places(Collection<String> contentIds) {
    return fanOut(contentIds, this::place);
  }

  // ── 내부 ──────────────────────────────────────────────────────────

  /**
   * 여러 건을 병렬로. 실패한 건은 결과에서 뺀다 — 빈 값으로 채우면 호출하는 쪽이
   * "없음"과 "실패"를 구분하지 못한다.
   */
  private <T> Map<String, Optional<T>> fanOut(
      Collection<String> ids, Function<String, Optional<T>> one) {
    Map<String, CompletableFuture<Optional<T>>> futures = new LinkedHashMap<>();
    for (String id : ids) {
      futures.putIfAbsent(id, CompletableFuture.supplyAsync(() -> one.apply(id), fanOut));
    }
    Map<String, Optional<T>> out = new LinkedHashMap<>();
    futures.forEach((id, f) -> {
      try {
        out.put(id, f.join());
      } catch (RuntimeException e) {
        log.warn("관광공사 조회 실패 contentId={}: {}", id, rootMessage(e));
      }
    });
    return out;
  }

  /** 호출 한 번. 성공하면 {@code response.body} 를 돌려준다. */
  private JsonNode call(String operation, String params) {
    String url = baseUrl + "/" + operation
        + "?MobileOS=ETC&MobileApp=meogeodo&_type=json&" + params
        + "&serviceKey=" + apiKey;
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();

    TourApiException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        permits.acquire();
        HttpResponse<String> response;
        try {
          response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } finally {
          permits.release();
        }
        return parse(operation, response.statusCode(), response.body());
      } catch (RetryableException e) {
        last = e;
        sleep(400L * attempt);
      } catch (IOException e) {
        last = new RetryableException(operation + " 네트워크 오류: " + e.getMessage());
        sleep(400L * attempt);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new TourApiException(operation + " 호출이 중단됐습니다", e);
      }
    }
    throw new TourApiException(operation + " 을(를) 불러오지 못했습니다: " + last.getMessage(), last);
  }

  private JsonNode parse(String operation, int status, String raw) {
    JsonNode root;
    try {
      root = json.readTree(raw);
    } catch (IOException e) {
      throw status == 429
          ? new RetryableException(operation + " 초당 한도 초과 (HTTP 429)")
          : new TourApiException(operation + " 응답을 읽지 못했습니다 (HTTP " + status + ")");
    }

    // 한도 초과·인증 오류는 response 없이 OpenAPI_ServiceResponse 로 온다.
    JsonNode fault = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
    if (!fault.isMissingNode()) {
      String code = fault.path("returnReasonCode").asText();
      String msg = fault.path("returnAuthMsg").asText(fault.path("errMsg").asText());
      if (RATE_PER_SECOND.equals(code) || status == 429) {
        throw new RetryableException(operation + " 초당 한도 초과");
      }
      if (RATE_PER_DAY.equals(code)) {
        throw new TourApiException(operation + " 일일 호출 한도 초과 — 내일 풀립니다");
      }
      throw new TourApiException(operation + " 오류 " + code + ": " + msg);
    }

    JsonNode response = root.path("response");
    String resultCode = response.path("header").path("resultCode").asText();
    if (!"0000".equals(resultCode) && !"00".equals(resultCode)) {
      throw new TourApiException(operation + " 오류 " + resultCode + ": "
          + response.path("header").path("resultMsg").asText());
    }
    return response.path("body");
  }

  /**
   * {@code body.items.item} 을 목록으로. 결과가 없으면 {@code items} 가 객체가 아니라
   * 빈 문자열로 오고, 한 건이면 배열이 아니라 객체로 오기도 한다.
   */
  private static List<JsonNode> items(JsonNode body) {
    JsonNode item = body.path("items").path("item");
    List<JsonNode> out = new ArrayList<>();
    if (item.isArray()) {
      item.forEach(out::add);
    } else if (item.isObject()) {
      out.add(item);
    }
    return out;
  }

  private static Place toPlace(JsonNode n) {
    return new Place(
        text(n, "contentid"),
        text(n, "title"),
        text(n, "addr1"),
        number(n, "mapy"), // 위도. mapx/mapy 를 뒤바꾸면 지도가 통째로 어긋난다
        number(n, "mapx"), // 경도
        number(n, "dist"),
        text(n, "firstimage"),
        text(n, "lDongRegnCd"),
        text(n, "lDongSignguCd"));
  }

  private static Intro toIntro(JsonNode n) {
    return new Intro(
        text(n, "firstmenu"),
        text(n, "treatmenu"),
        text(n, "infocenterfood"),
        text(n, "opentimefood"),
        text(n, "restdatefood"),
        text(n, "parkingfood"));
  }

  private static String text(JsonNode n, String field) {
    String v = n.path(field).asText("").trim();
    return v.isEmpty() ? null : v;
  }

  private static Double number(JsonNode n, String field) {
    String v = text(n, field);
    try {
      return v == null ? null : Double.valueOf(v);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * contentId 는 양의 정수다. URL 에 그대로 붙이므로 다른 문자가 섞인 값은 부르지
   * 않고 "없는 식당"으로 답한다.
   */
  private static boolean isContentId(String contentId) {
    return contentId != null && contentId.matches("[1-9]\\d{0,17}");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String rootMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    return t.getMessage();
  }

  /** 잠깐 쉬었다 다시 하면 되는 실패. 호출부 밖으로 새지 않는다. */
  private static final class RetryableException extends TourApiException {
    RetryableException(String message) {
      super(message);
    }
  }
}
