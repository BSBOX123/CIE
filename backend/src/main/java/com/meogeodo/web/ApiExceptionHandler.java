package com.meogeodo.web;

import com.meogeodo.tour.TourApiException;
import com.meogeodo.user.UserService.DuplicateLoginIdException;
import com.meogeodo.user.UserService.UnauthorizedException;
import com.meogeodo.web.AuthController.ApiError;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 공통 오류 응답 (SPEC 9.1). */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /**
   * 관광공사 API 를 불러오지 못했다. 식당·메뉴는 실시간으로만 받으므로 빈 결과로
   * 답하지 않는다 — "근처에 식당이 없다"로 읽히면 안 된다.
   */
  @ExceptionHandler(TourApiException.class)
  public ResponseEntity<ApiError> tourApiUnavailable(TourApiException e) {
    log.warn("관광공사 API 실패: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new ApiError(
            "TOUR_API_UNAVAILABLE", "관광 정보를 잠시 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."));
  }

  @ExceptionHandler(DuplicateLoginIdException.class)
  public ResponseEntity<ApiError> duplicateLoginId(DuplicateLoginIdException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ApiError("DUPLICATE_LOGIN_ID", e.getMessage()));
  }

  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<ApiError> unauthorized(UnauthorizedException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(new ApiError("UNAUTHORIZED", "로그인이 필요합니다."));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
    String detail =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining(", "));
    return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", detail));
  }
}
