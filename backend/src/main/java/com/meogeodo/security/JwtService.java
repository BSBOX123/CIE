package com.meogeodo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 액세스 토큰 발급·검증 (SPEC 9.1, D5). */
@Service
public class JwtService {

  private final SecretKey key;
  private final Duration accessTokenTtl;

  public JwtService(
      @Value("${meogeodo.security.jwt-secret:}") String secret,
      @Value("${meogeodo.security.access-token-ttl:PT12H}") Duration accessTokenTtl) {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException(
          "meogeodo.security.jwt-secret 은 32바이트 이상이어야 합니다. "
              + "환경변수 JWT_SECRET 를 설정하세요. 생성: openssl rand -base64 48");
    }
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.accessTokenTtl = accessTokenTtl;
  }

  public String issue(Long userId, String loginId) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(String.valueOf(userId))
        .claim("loginId", loginId)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(accessTokenTtl)))
        .signWith(key)
        .compact();
  }

  /**
   * 토큰에서 사용자 id를 꺼낸다.
   *
   * @return 유효하지 않으면 {@code null}. 만료·위조를 구분하지 않는다 —
   *     클라이언트에게 알려줄 이유가 없다.
   *     ({@code NumberFormatException} 은 {@code IllegalArgumentException} 의
   *     하위라 함께 잡힌다.)
   */
  public Long resolveUserId(String token) {
    try {
      Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
      return Long.valueOf(claims.getSubject());
    } catch (JwtException | IllegalArgumentException e) {
      return null;
    }
  }

  public long accessTokenSeconds() {
    return accessTokenTtl.toSeconds();
  }
}
