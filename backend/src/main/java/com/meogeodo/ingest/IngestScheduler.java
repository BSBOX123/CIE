package com.meogeodo.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 식당 인제스트 스케줄 (SPEC 7.4).
 *
 * <p>전국 13,495건 완주에 약 45분 걸린다(실측). {@code modifiedtime} 비교로
 * 변경분만 상세 조회하므로 2회차부터는 훨씬 짧다.
 */
@Component
@ConditionalOnProperty(name = "meogeodo.ingest.scheduled", havingValue = "true")
public class IngestScheduler {

  private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

  private final IngestService ingest;

  public IngestScheduler(IngestService ingest) {
    this.ingest = ingest;
  }

  /** 매일 새벽 2시. 태깅 제출(3시 30분)보다 앞선다. */
  @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
  public void ingestNationwide() {
    try {
      log.info("전국 인제스트 시작");
      log.info("전국 인제스트 완료 — {}", ingest.ingestArea(null, 0));
    } catch (RuntimeException e) {
      log.error("인제스트 실패", e);
    }
  }
}
