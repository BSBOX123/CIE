package com.meogeodo.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meogeodo.user.AppUser;
import com.meogeodo.user.UserService;
import com.meogeodo.web.AuthDtos.SignupRequest;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 트랜잭션 경계 회귀 테스트.
 *
 * <p><b>이 클래스에는 일부러 {@code @Transactional} 을 붙이지 않았다.</b> 다른
 * 테스트들은 트랜잭션이 전체를 감싸서, 컨트롤러가 준영속 엔티티를 넘겨도
 * 지연 로딩이 성공해 버렸다. 실제 서버에서는 요청마다 트랜잭션이 끝나므로
 * 같은 코드가 터진다 — 실제로 401(오류 포워딩에 가려진 500)로 겪었다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProfileTransactionTest {

  @Autowired private UserService userService;

  private AppUser signup(String loginId) {
    return userService.signup(new SignupRequest(
        loginId, "secret123", "김영수", "남성", 1958, "A형",
        Set.of("당뇨", "고혈압"), Set.of(), Set.of("새우"), false,
        Set.of("혈압약"), null, false));
  }

  @Test
  @DisplayName("트랜잭션 밖에서 호출해도 지연 로딩 컬렉션을 읽을 수 있다")
  void loadsCollectionsOutsideTransaction() {
    Long userId = signup("tx-test-1").getId();

    // 여기는 트랜잭션이 없다. viewById 가 스스로 트랜잭션을 열어야 한다.
    var profile = userService.viewById(userId);

    assertThat(profile.diseases()).containsExactlyInAnyOrder("당뇨", "고혈압");
    assertThat(profile.cares()).isNotEmpty();
    assertThat(profile.allergies()).containsExactly("새우");
    assertThat(profile.medications()).containsExactly("혈압약");
  }

  @Test
  @DisplayName("수정도 트랜잭션 밖 호출을 견딘다")
  void updatesOutsideTransaction() {
    Long userId = signup("tx-test-2").getId();

    var updated = userService.updateById(userId,
        new AuthDtos.ProfileUpdateRequest(null, null, null, null,
            Set.of("만성콩팥병"), null, null, null, null, null, null, null));

    assertThat(updated.cares())
        .extracting(AuthDtos.CareView::value)
        .containsExactlyInAnyOrder("나트륨", "칼륨", "단백질량");
  }

  @Test
  @DisplayName("없는 사용자는 UnauthorizedException")
  void unknownUser() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> userService.viewById(999_999L))
        .isInstanceOf(UserService.UnauthorizedException.class);
  }

  @Test
  @DisplayName("userId 가 null 이면 UnauthorizedException")
  void nullUser() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> userService.viewById(null))
        .isInstanceOf(UserService.UnauthorizedException.class);
  }
}
