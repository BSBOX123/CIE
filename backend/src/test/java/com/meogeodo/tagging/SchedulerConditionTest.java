package com.meogeodo.tagging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 배치 스케줄러가 설정으로 켜지는지.
 *
 * <p>운영에서 {@code TAGGING_SCHEDULED=true} 를 넣었는데도 배치가 돌지 않아
 * 확인용으로 만들었다. 조건이 깨지면 태깅 큐가 영영 줄지 않는데, 에러가
 * 나지 않아 알아채기 어렵다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "meogeodo.tagging.scheduled=true")
class SchedulerConditionTest {

  @Autowired private ApplicationContext context;

  @Test
  @DisplayName("scheduled=true 면 TaggingScheduler 빈이 만들어진다")
  void schedulerBeanExists() {
    assertThat(context.getBeanNamesForType(TaggingScheduler.class)).isNotEmpty();
  }
}
