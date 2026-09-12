package com.meogeodo.web;

import com.meogeodo.user.UserService;
import com.meogeodo.web.AuthDtos.ProfileResponse;
import com.meogeodo.web.AuthDtos.ProfileUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 정보 (SPEC 9.2, 화면 10·11).
 *
 * <p>사용자 조회를 서비스에 맡긴다. 여기서 엔티티를 읽어 넘기면 트랜잭션
 * 밖이라 지연 로딩 컬렉션에서 터진다.
 */
@RestController
@RequestMapping("/api/me")
@Tag(name = "2. 내 정보", description = "로그인한 사용자의 프로필. 판정의 기준이 되는 값들이다.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "토큰이 없거나 만료됨")
public class ProfileController {

  private final UserService userService;

  public ProfileController(UserService userService) {
    this.userService = userService;
  }

  @Operation(
      summary = "내 정보 조회",
      description =
          """
          `cares`(주의성분) 의 `auto` 가 `true` 면 질환에서 자동으로 따라온 항목이다.
          사용자가 직접 고른 것이 아니므로 화면에서 구분해 보여줄 것.""")
  @GetMapping("/profile")
  public ProfileResponse profile(@AuthenticationPrincipal Long userId) {
    return userService.viewById(userId);
  }

  @Operation(
      summary = "내 정보 수정",
      description =
          """
          **보낸 항목만 바꾼다.** 필드를 빼거나 `null` 로 보내면 "그대로 두기" 다.
          어떤 값을 비우려면 빈 배열 `[]` 또는 빈 문자열을 명시적으로 보낼 것.

          질환을 바꾸면 그에 딸린 주의성분(`auto=true`)도 다시 계산된다.""")
  @PutMapping("/profile")
  public ResponseEntity<ProfileResponse> update(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody ProfileUpdateRequest request) {
    return ResponseEntity.ok(userService.updateById(userId, request));
  }
}
