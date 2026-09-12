package com.meogeodo.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

/** 인증·프로필 API 요청/응답 (SPEC 9.2). */
public final class AuthDtos {

  private AuthDtos() {}

  /**
   * 회원가입. 프론트 5단계에서 모은 값을 한 번에 보낸다 (SPEC 10.1).
   *
   * <p>질환·주의성분·알레르기는 어휘 정본에 있는 값만 받는다. 철자가 다르면
   * 판정 시 교집합이 비어 조용히 실패하므로, 저장 전에 거른다.
   */
  @Schema(description = "회원가입 요청. 프론트 5단계에서 모은 값을 한 번에 보낸다")
  public record SignupRequest(
      @Schema(description = "로그인 아이디", example = "meog0112", requiredMode = Schema.RequiredMode.REQUIRED)
          @NotBlank
          @Size(min = 4, max = 50)
          String loginId,
      @Schema(description = "비밀번호 (6자 이상)", example = "secret123",
              requiredMode = Schema.RequiredMode.REQUIRED)
          @NotBlank
          @Size(min = 6, max = 100)
          String password,
      @Schema(description = "이름", example = "김영수", requiredMode = Schema.RequiredMode.REQUIRED)
          @NotBlank
          @Size(max = 50)
          String name,
      @Schema(description = "성별", example = "남성") String gender,
      @Schema(description = "태어난 해. 카드의 연령대 표기에 쓴다", example = "1958")
          @Min(1900)
          @Max(2100)
          Integer birthYear,
      @Schema(description = "혈액형", example = "A형") String bloodType,
      @Schema(description = "지병. 어휘 정본에 있는 값만", example = "[\"당뇨\", \"고혈압\"]")
          Set<String> diseases,
      @Schema(description = "직접 고른 주의성분. 질환에서 자동으로 따라오는 것은 보내지 않아도 된다",
              example = "[\"나트륨\"]")
          Set<String> cares,
      @Schema(description = "알레르기. 어휘 정본에 있는 값만", example = "[\"새우\", \"고등어\"]")
          Set<String> allergies,
      @Schema(description = "씹기 어려움 여부", example = "false") boolean chewingDifficulty,
      @Schema(description = "복용 중인 약", example = "[\"혈압약\"]") Set<String> medications,
      @Schema(description = "약에 대한 자유 메모") String medNote,
      @Schema(description = "주문요청카드에 복용약을 노출할지", example = "false")
          boolean showMedsOnCard) {}

  @Schema(description = "로그인 요청")
  public record LoginRequest(
      @Schema(example = "meog0112", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank
          String loginId,
      @Schema(example = "secret123", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank
          String password) {}

  @Schema(description = "발급된 접근 토큰")
  public record TokenResponse(
      @Schema(description = "이후 요청에 Authorization: Bearer <이 값> 으로 보낸다")
          String accessToken,
      @Schema(description = "만료까지 남은 초", example = "43200") long expiresIn,
      @Schema(example = "Bearer") String tokenType) {
    public static TokenResponse bearer(String token, long seconds) {
      return new TokenResponse(token, seconds, "Bearer");
    }
  }

  /** 주의성분 1건. {@code auto} 는 질환에서 자동으로 따라온 것인지 (SPEC 2.3). */
  @Schema(description = "주의성분 1건")
  public record CareView(
      @Schema(description = "성분 이름", example = "나트륨") String value,
      @Schema(description = "true 면 질환에서 자동으로 따라온 것. 사용자가 고른 것과 구분해 보여줄 것",
              example = "true")
          boolean auto,
      @Schema(description = "왜 주의해야 하는지", example = "고혈압") String note) {}

  @Schema(description = "내 정보")
  public record ProfileResponse(
      @Schema(example = "meog0112") String loginId,
      String name,
      String gender,
      Integer birthYear,
      String bloodType,
      List<String> diseases,
      List<CareView> cares,
      List<String> allergies,
      boolean chewingDifficulty,
      List<String> medications,
      String medNote,
      boolean showMedsOnCard,
      @Schema(description = "글자 크기 단계 (0~4)", example = "0") int fontScaleIdx,
      @Schema(description = "화면에 그대로 노출해야 하는 고지 문구") String disclaimer) {}

  /** 프로필 수정. 보낸 항목만 바꾼다 — {@code null} 은 "그대로 두기". */
  @Schema(description = "프로필 수정 요청. 보낸 항목만 바뀌고, 빠진 항목/null 은 그대로 둔다")
  public record ProfileUpdateRequest(
      @Size(max = 50) String name,
      String gender,
      @Min(1900) @Max(2100) Integer birthYear,
      String bloodType,
      Set<String> diseases,
      Set<String> cares,
      Set<String> allergies,
      Boolean chewingDifficulty,
      Set<String> medications,
      String medNote,
      Boolean showMedsOnCard,
      @Min(0) @Max(4) Integer fontScaleIdx) {}
}
