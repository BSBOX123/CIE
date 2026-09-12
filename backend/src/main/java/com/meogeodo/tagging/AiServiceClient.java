package com.meogeodo.tagging;

import com.meogeodo.tagging.AiServiceDtos.BulkTagRequest;
import com.meogeodo.tagging.AiServiceDtos.BulkTagResponse;
import com.meogeodo.tagging.AiServiceDtos.NutritionLookupResponse;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ai-service(FastAPI) 호출.
 *
 * <p>FastAPI 쪽은 snake_case를 쓰고 Java 쪽은 camelCase를 쓰므로 이 클라이언트
 * 전용 {@code ObjectMapper} 로 변환한다. 전역 설정을 바꾸면 다른 API 응답까지
 * 영향을 받는다.
 */
@Component
public class AiServiceClient {

  private final RestClient restClient;

  public AiServiceClient(@Value("${meogeodo.ai-service.base-url}") String baseUrl) {
    ObjectMapper mapper =
        JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();
    MappingJackson2HttpMessageConverter converter =
        new MappingJackson2HttpMessageConverter(mapper);

    // 무료 티어의 분당 요청 한도를 지키느라 태깅 한 번에 수 분이 걸린다.
    // 기본 타임아웃으로는 모자라다.
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(10));
    factory.setReadTimeout(Duration.ofMinutes(10));

    this.restClient =
        RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .messageConverters(converters -> converters.add(0, converter))
            .build();
  }

  /** 음식명 여러 건의 영양성분을 조회한다. 매칭 실패한 이름은 결과에 없다. */
  public NutritionLookupResponse lookupNutrition(List<String> dishNames) {
    return restClient
        .post()
        .uri("/nutrition/lookup")
        .contentType(MediaType.APPLICATION_JSON)
        .body(dishNames)
        .retrieve()
        .body(NutritionLookupResponse.class);
  }

  /**
   * 음식 여러 건을 태깅한다.
   *
   * <p>ai-service가 분당 요청 한도에 맞춰 간격을 두고 호출하므로 응답까지 수
   * 분이 걸릴 수 있다. 한 건이 실패해도 나머지는 처리되며, 실패 목록이 함께
   * 돌아온다.
   */
  public BulkTagResponse tagDishes(BulkTagRequest request) {
    return restClient
        .post()
        .uri("/tag/dishes")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .retrieve()
        .body(BulkTagResponse.class);
  }
}
