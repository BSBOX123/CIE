package com.meogeodo.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 민감정보 암호화 키를 {@link SensitiveDataConverter} 에 넣어 준다.
 *
 * <p>컨버터는 JPA가 직접 생성하므로 스프링 주입을 받을 수 없다. 그래서 키
 * 주입을 이 빈이 대신한다. 컨버터 자신에게 {@code @Value} 생성자를 두면
 * JPA가 쓰는 무인자 생성자와 둘이 되어, 스프링이 무인자 쪽을 골라 <b>키가
 * 조용히 비는</b> 일이 생긴다(실제로 겪었다).
 */
@Component
public class EncryptionKeyHolder {

  private static final Logger log = LoggerFactory.getLogger(EncryptionKeyHolder.class);

  private final String configuredKey;

  public EncryptionKeyHolder(
      @Value("${meogeodo.security.encryption-key:}") String configuredKey) {
    this.configuredKey = configuredKey;
  }

  @PostConstruct
  public void apply() {
    SensitiveDataConverter.applyKey(configuredKey);
    if (configuredKey == null || configuredKey.isBlank()) {
      // 기동 자체를 막지는 않는다. 건강정보를 쓰지 않는 경로(식당 조회 등)는
      // 동작해야 하기 때문이다. 저장 시점에 명확한 오류로 실패한다.
      log.warn("암호화 키가 없습니다. 건강정보 저장이 실패합니다. "
          + "MEOGEODO_ENCRYPTION_KEY 를 설정하세요.");
    }
  }
}
