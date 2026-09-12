package com.meogeodo.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
/**
 * 민감정보 컬럼 암호화 (SPEC 11.4).
 *
 * <p>질환·알레르기·복용약은 <b>건강정보</b>이며 개인정보보호법상 민감정보로
 * 분류된다. 저장 시 암호화가 요구된다.
 *
 * <p>AES-GCM을 쓰고 매번 새 IV를 생성하므로 같은 값도 매번 다른 암호문이 된다.
 * 따라서 <b>암호화된 컬럼으로는 검색·조인을 할 수 없다.</b> 본 서비스의 판정은
 * 사용자 한 명의 값을 모두 읽어 메모리에서 교집합을 구하므로 문제가 없다
 * (SPEC 3.1).
 *
 * <p>키가 없으면 기동을 거부한다. 건강정보를 평문으로 저장한 채 조용히 동작하는
 * 것이 가장 나쁜 결과이기 때문이다.
 */
@Converter
public class SensitiveDataConverter implements AttributeConverter<String, String> {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_LENGTH = 12;
  private static final int TAG_BITS = 128;

  private static SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  /**
   * JPA가 이 컨버터를 직접 생성하므로 스프링 주입을 받을 수 없다. 키는
   * {@link EncryptionKeyHolder} 가 기동 시 정적 필드에 채운다.
   */
  public SensitiveDataConverter() {}

  static SecretKeySpec buildKey(String configured) {
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(configured);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "meogeodo.security.encryption-key 는 Base64 로 인코딩된 값이어야 합니다", e);
    }
    if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
      throw new IllegalStateException(
          "암호화 키 길이가 잘못되었습니다: " + decoded.length + "바이트 (16/24/32 필요)");
    }
    return new SecretKeySpec(decoded, "AES");
  }

  private static SecretKeySpec requireKey() {
    if (key == null) {
      throw new IllegalStateException(
          "건강정보를 저장하려면 암호화 키가 필요합니다. "
              + "환경변수 MEOGEODO_ENCRYPTION_KEY 를 설정하세요. "
              + "생성: openssl rand -base64 32");
    }
    return key;
  }

  @Override
  public String convertToDatabaseColumn(String plain) {
    if (plain == null) {
      return null;
    }
    try {
      byte[] iv = new byte[IV_LENGTH];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
      byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

      byte[] combined = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("민감정보 암호화 실패", e);
    }
  }

  @Override
  public String convertToEntityAttribute(String stored) {
    if (stored == null) {
      return null;
    }
    try {
      byte[] combined = Base64.getDecoder().decode(stored);
      byte[] iv = new byte[IV_LENGTH];
      System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
      byte[] decrypted =
          cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
      return new String(decrypted, StandardCharsets.UTF_8);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("민감정보 복호화 실패", e);
    }
  }

  /** {@link EncryptionKeyHolder} 와 테스트가 키를 넣는 통로. */
  static void applyKey(String base64Key) {
    key = base64Key == null || base64Key.isBlank() ? null : buildKey(base64Key);
  }
}
