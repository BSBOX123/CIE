package com.meogeodo.web;

import com.meogeodo.user.UserService.DuplicateLoginIdException;
import com.meogeodo.user.UserService.UnauthorizedException;
import com.meogeodo.web.AuthController.ApiError;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 공통 오류 응답 (SPEC 9.1). */
@RestControllerAdvice
public class ApiExceptionHandler {

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
