package com.meogeodo.web;

import com.meogeodo.security.JwtService;
import com.meogeodo.user.AppUser;
import com.meogeodo.user.AppUserRepository;
import com.meogeodo.user.UserService;
import com.meogeodo.web.AuthDtos.LoginRequest;
import com.meogeodo.web.AuthDtos.SignupRequest;
import com.meogeodo.web.AuthDtos.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원가입·로그인 (SPEC 9.2). */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "1. 인증", description = "회원가입·로그인. 토큰이 필요 없는 유일한 구간이다.")
public class AuthController {

  private final UserService userService;
  private final AppUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;

  public AuthController(
      UserService userService,
      AppUserRepository users,
      PasswordEncoder passwordEncoder,
      JwtService jwtService) {
    this.userService = userService;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
  }

  @Operation(
      summary = "회원가입",
      description =
          """
          프론트 5단계에서 모은 값을 한 번에 보낸다. 성공하면 바로 로그인된 상태의
          토큰을 돌려주므로, 가입 후 따로 로그인을 호출할 필요가 없다.

          질환·주의성분·알레르기는 **어휘 정본에 있는 값만** 받는다. 철자가 다르면
          판정 시 교집합이 비어 조용히 실패하기 때문이다.""")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "가입 성공. 토큰을 함께 돌려준다"),
    @ApiResponse(
        responseCode = "400",
        description = "입력값이 규격에 맞지 않음 (code=INVALID_REQUEST)",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "409",
        description = "이미 쓰고 있는 아이디 (code=DUPLICATE_LOGIN_ID)",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @PostMapping("/signup")
  public ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
    AppUser user = userService.signup(request);
    String token = jwtService.issue(user.getId(), user.getLoginId());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(TokenResponse.bearer(token, jwtService.accessTokenSeconds()));
  }

  /**
   * 로그인.
   *
   * <p>아이디가 없는 경우와 비밀번호가 틀린 경우를 구분해 알리지 않는다.
   * 구분해 주면 어떤 아이디가 가입되어 있는지 알아낼 수 있다.
   */
  @Operation(
      summary = "로그인",
      description =
          """
          아이디가 없는 경우와 비밀번호가 틀린 경우를 **구분해 알리지 않는다.**
          구분해 주면 어떤 아이디가 가입되어 있는지 알아낼 수 있기 때문이다.
          프론트도 두 경우를 같은 문구로 안내할 것.""")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "로그인 성공",
        content = @Content(schema = @Schema(implementation = TokenResponse.class))),
    @ApiResponse(
        responseCode = "401",
        description = "아이디 또는 비밀번호가 틀림 (code=INVALID_CREDENTIALS)",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
    AppUser user = users.findByLoginId(request.loginId()).orElse(null);
    if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(new ApiError("INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다."));
    }
    String token = jwtService.issue(user.getId(), user.getLoginId());
    return ResponseEntity.ok(TokenResponse.bearer(token, jwtService.accessTokenSeconds()));
  }

  /** 오류 응답 (SPEC 9.1). */
  @Schema(name = "ApiError", description = "모든 오류 응답의 공통 형식")
  public record ApiError(
      @Schema(description = "오류 코드. 분기는 이 값으로 할 것", example = "INVALID_CREDENTIALS")
          String code,
      @Schema(description = "사용자에게 보여도 되는 문구", example = "아이디 또는 비밀번호가 올바르지 않습니다.")
          String message) {}
}
