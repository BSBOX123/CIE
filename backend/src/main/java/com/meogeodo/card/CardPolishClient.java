package com.meogeodo.card;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.meogeodo.card.CardDtos.PolishRequest;
import com.meogeodo.card.CardDtos.PolishResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ai-service 의 문구 다듬기 호출.
 *
 * <p>카드 생성은 사용자가 매장 앞에서 기다리는 화면이다. 다듬기 때문에 오래
 * 붙잡아 둘 수 없으므로 타임아웃을 짧게 잡고, 실패하면 원문으로 넘어간다.
 */
@Component
public class CardPolishClient {

  private static final Logger log = LoggerFactory.getLogger(CardPolishClient.class);

  private final RestClient restClient;

  public CardPolishClient(@Value("${meogeodo.ai-service.base-url}") String baseUrl) {
    ObjectMapper mapper = JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .build();
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(8));

    this.restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(factory)
        .messageConverters(c -> c.add(0, new MappingJackson2HttpMessageConverter(mapper)))
        .build();
  }

  /** @return 실패하면 {@code null} — 호출부가 원문을 쓴다. */
  public PolishResponse polish(List<String> phrases, List<String> mustKeep, String menuLabel) {
    try {
      return restClient.post()
          .uri("/card/polish")
          .contentType(MediaType.APPLICATION_JSON)
          .body(new PolishRequest(phrases, mustKeep, menuLabel))
          .retrieve()
          .body(PolishResponse.class);
    } catch (RuntimeException e) {
      log.warn("문구 다듬기 호출 실패, 원문을 씁니다: {}", e.getMessage());
      return null;
    }
  }
}
