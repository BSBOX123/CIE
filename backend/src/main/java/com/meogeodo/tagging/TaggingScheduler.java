package com.meogeodo.tagging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 태깅 스케줄 (SPEC 7.4).
 *
 * <p>무료 티어는 일일 요청 수가 제한되므로 한 번에 몰아서 처리할 수 없다.
 * 정해진 개수씩 주기적으로 돌면서 미태깅 큐를 줄여 나간다. 며칠에 걸쳐
 * 나눠 도는 것이 정상 동작이다.
 *
 * <p>테스트와 로컬 개발에서 원치 않게 도는 것을 막으려고 기본값은 꺼짐이다.
 */
@Component
@ConditionalOnProperty(name = "meogeodo.tagging.scheduled", havingValue = "true")
public class TaggingScheduler {

  private static final Logger log = LoggerFactory.getLogger(TaggingScheduler.class);

  private final TaggingService tagging;

  public TaggingScheduler(TaggingService tagging) {
    this.tagging = tagging;
  }

  /**
   * 30분마다 한 묶음씩.
   *
   * <p>간격을 좁히면 분당 요청 한도에, 넓히면 일일 한도를 다 못 쓰고 하루가
   * 지난다. 실제 한도를 확인한 뒤 {@code meogeodo.tagging.chunk-size} 와 함께
   * 조정한다.
   */
  @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT1M")
  public void tag() {
    try {
      int tagged = tagging.tagPending();
      if (tagged > 0) {
        log.info("태깅 {}건 완료", tagged);
      }
    } catch (RuntimeException e) {
      log.error("태깅 실행 실패", e);
    }
  }
}
