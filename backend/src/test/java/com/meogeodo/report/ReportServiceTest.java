package com.meogeodo.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meogeodo.domain.Restaurant;
import com.meogeodo.domain.RestaurantFlag;
import com.meogeodo.domain.RestaurantFlagRepository;
import com.meogeodo.domain.RestaurantRepository;
import com.meogeodo.report.ReportDtos.CreateRequest;
import com.meogeodo.user.AppUser;
import com.meogeodo.user.UserService;
import com.meogeodo.web.AuthDtos.SignupRequest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 제보 처리 테스트 (MVP 4번). */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportServiceTest {

  @Autowired private ReportService reports;
  @Autowired private RestaurantRepository restaurants;
  @Autowired private RestaurantFlagRepository flags;
  @Autowired private UserService userService;
  @Autowired private EntityManager em;

  private Restaurant restaurant;

  @BeforeEach
  void setUp() {
    Restaurant r = new Restaurant("c-report", "초당할머니순두부");
    r.setLat(BigDecimal.valueOf(37.76));
    r.setLng(BigDecimal.valueOf(128.93));
    restaurant = restaurants.save(r);
    em.flush();
  }

  private AppUser signup(String loginId, Integer birthYear, Set<String> diseases) {
    return userService.signup(new SignupRequest(
        loginId, "secret123", "김영수", "남성", birthYear, "A형",
        diseases, Set.of(), Set.of("새우"), false, Set.of(), null, false));
  }

  private Long report(AppUser user, boolean ok, List<String> requests, Set<String> feedback) {
    return reports.create(user.getId(),
        new CreateRequest(restaurant.getId(), ok, "메모", requests, feedback)).id();
  }

  @Nested
  @DisplayName("제보 등록")
  class Create {

    @Test
    @DisplayName("평가와 주문 방법이 함께 저장된다")
    void savesEvaluationAndRequests() {
      var user = signup("rp1", 1958, Set.of("당뇨"));

      var view = reports.create(user.getId(), new CreateRequest(
          restaurant.getId(), true, "짜지 않은 순두부로 주문했습니다.",
          List.of("양념장은 따로 주세요", "김치·젓갈 반찬은 빼 주세요"),
          Set.of("요청이 그대로 전달됐어요")));

      assertThat(view.ok()).isTrue();
      assertThat(view.symbol()).isEqualTo("○");
      assertThat(view.requests())
          .containsExactly("양념장은 따로 주세요", "김치·젓갈 반찬은 빼 주세요");
      assertThat(view.feedback()).containsExactly("요청이 그대로 전달됐어요");
    }

    @Test
    @DisplayName("요청이 거절된 제보도 남길 수 있다")
    void negativeReport() {
      var user = signup("rp2", 1970, Set.of("고혈압"));

      var view = reports.create(user.getId(), new CreateRequest(
          restaurant.getId(), false, "주방까지 전달되지 않았습니다.",
          List.of(), Set.of("요청을 전하기 어려웠어요")));

      assertThat(view.ok()).isFalse();
      assertThat(view.symbol()).isEqualTo("✕");
    }

    @Test
    @DisplayName("정해지지 않은 피드백 문구는 버린다")
    void rejectsUnknownFeedback() {
      var user = signup("rp3", 1958, Set.of());

      var view = reports.create(user.getId(), new CreateRequest(
          restaurant.getId(), true, null, List.of(),
          Set.of("요청이 그대로 전달됐어요", "제가 지어낸 문구")));

      assertThat(view.feedback()).containsExactly("요청이 그대로 전달됐어요");
    }

    @Test
    @DisplayName("없는 식당에는 제보할 수 없다")
    void unknownRestaurant() {
      var user = signup("rp4", 1958, Set.of());
      assertThatThrownBy(() -> reports.create(user.getId(),
          new CreateRequest(999_999L, true, null, List.of(), Set.of())))
          .hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    @DisplayName("비로그인은 제보할 수 없다")
    void anonymous() {
      assertThatThrownBy(() -> reports.create(null,
          new CreateRequest(restaurant.getId(), true, null, List.of(), Set.of())))
          .isInstanceOf(UserService.UnauthorizedException.class);
    }
  }

  @Nested
  @DisplayName("작성자 표시 — 건강정보를 그대로 노출하지 않는다 (SPEC 11.4)")
  class Author {

    @Test
    @DisplayName("연령대와 대표 질환만 보여준다")
    void ageBandAndOneDisease() {
      var author = signup("au1", 1958, Set.of("당뇨", "고혈압"));
      var viewer = signup("au2", 1990, Set.of());
      report(author, true, List.of(), Set.of());
      em.flush();

      var list = reports.listForRestaurant(viewer.getId(), restaurant.getId());
      String label = list.reviews().get(0).author();

      assertThat(label).contains("대");           // 60대
      assertThat(label).doesNotContain("김영수");   // 이름 없음
      assertThat(label).doesNotContain("1958");   // 정확한 나이 없음
      // 질환은 하나만 — 전부 나열하면 개인을 좁힐 수 있다
      assertThat(label.split("·")).hasSize(2);
    }

    @Test
    @DisplayName("본인에게는 '내 후기' 로 보인다")
    void ownLabel() {
      var user = signup("au3", 1958, Set.of("당뇨"));
      report(user, true, List.of(), Set.of());
      em.flush();

      var list = reports.listForRestaurant(user.getId(), restaurant.getId());
      assertThat(list.reviews().get(0).author()).isEqualTo("내 후기");
      assertThat(list.reviews().get(0).mine()).isTrue();
    }

    @Test
    @DisplayName("출생년도가 없으면 연령대를 만들지 않는다")
    void noBirthYear() {
      var author = signup("au4", null, Set.of("통풍"));
      var viewer = signup("au5", 1990, Set.of());
      report(author, true, List.of(), Set.of());
      em.flush();

      assertThat(reports.listForRestaurant(viewer.getId(), restaurant.getId())
          .reviews().get(0).author()).isEqualTo("통풍");
    }
  }

  @Nested
  @DisplayName("식당 속성 도출 (SPEC 6.5) — 공공데이터로는 채울 수 없는 값")
  class FlagDerivation {

    private Set<String> flagsOf() {
      return flags.findByRestaurantIdIn(List.of(restaurant.getId())).stream()
          .map(RestaurantFlag::getFlag)
          .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("제보 1건으로는 속성을 부여하지 않는다")
    void oneReportIsNotEnough() {
      // 한 사람의 경험일 뿐이다. 그날 그 직원만 그랬을 수도 있다.
      report(signup("fl1", 1958, Set.of()), true, List.of(),
          Set.of("간을 약하게 해 주셨어요"));
      em.flush();

      assertThat(flagsOf()).isEmpty();
    }

    @Test
    @DisplayName("2건이 모이면 속성이 붙는다")
    void twoReportsQualify() {
      report(signup("fl2", 1958, Set.of()), true, List.of(),
          Set.of("간을 약하게 해 주셨어요"));
      report(signup("fl3", 1960, Set.of()), true, List.of(),
          Set.of("간을 약하게 해 주셨어요"));
      em.flush();

      assertThat(flagsOf()).contains("저염 요청 가능");
    }

    @Test
    @DisplayName("다른 문구라도 같은 속성으로 이어지면 합산된다")
    void differentPhrasesSameFlag() {
      report(signup("fl4", 1958, Set.of()), true, List.of(),
          Set.of("간을 약하게 해 주셨어요"));
      report(signup("fl5", 1960, Set.of()), true, List.of(),
          Set.of("국물을 따로 담아 주셨어요"));
      em.flush();

      assertThat(flagsOf()).contains("저염 요청 가능");
    }

    @Test
    @DisplayName("속성으로 이어지지 않는 문구는 아무것도 만들지 않는다")
    void nonMappingFeedback() {
      report(signup("fl6", 1958, Set.of()), true, List.of(),
          Set.of("직원이 다시 확인해 주셨어요"));
      report(signup("fl7", 1960, Set.of()), true, List.of(),
          Set.of("직원이 다시 확인해 주셨어요"));
      em.flush();

      assertThat(flagsOf()).isEmpty();
    }

    @Test
    @DisplayName("제보를 지우면 근거가 모자라 속성도 내려간다")
    void removingReportDropsFlag() {
      var u1 = signup("fl8", 1958, Set.of());
      var u2 = signup("fl9", 1960, Set.of());
      Long first = report(u1, true, List.of(), Set.of("메뉴에 재료 표기가 있었어요"));
      report(u2, true, List.of(), Set.of("메뉴에 재료 표기가 있었어요"));
      em.flush();
      assertThat(flagsOf()).contains("알레르기 표기 있음");

      reports.delete(u1.getId(), first);
      em.flush();

      assertThat(flagsOf()).doesNotContain("알레르기 표기 있음");
    }

    @Test
    @DisplayName("관리자가 넣은 속성은 후기 재계산이 건드리지 않는다")
    void adminFlagsSurvive() {
      flags.save(new RestaurantFlag(
          restaurant.getId(), "경사로", RestaurantFlag.Source.ADMIN));
      em.flush();

      report(signup("fl10", 1958, Set.of()), true, List.of(),
          Set.of("간을 약하게 해 주셨어요"));
      em.flush();

      assertThat(flagsOf()).contains("경사로");
    }

    @Test
    @DisplayName("도출된 속성이 목록 응답에 함께 온다")
    void listedInResponse() {
      report(signup("fl11", 1958, Set.of()), true, List.of(),
          Set.of("간을 약하게 해 주셨어요"));
      report(signup("fl12", 1960, Set.of()), true, List.of(),
          Set.of("간을 약하게 해 주셨어요"));
      em.flush();

      assertThat(reports.listForRestaurant(null, restaurant.getId()).derivedFlags())
          .contains("저염 요청 가능");
    }
  }

  @Nested
  @DisplayName("조회·삭제")
  class Access {

    @Test
    @DisplayName("내 제보 목록은 내 방문 기록이 된다")
    void myReports() {
      var user = signup("ac1", 1958, Set.of("당뇨"));
      report(user, true, List.of("국물은 따로 담아 주세요"), Set.of());
      em.flush();

      var mine = reports.listMine(user.getId());
      assertThat(mine).hasSize(1);
      assertThat(mine.get(0).requests()).containsExactly("국물은 따로 담아 주세요");
      assertThat(mine.get(0).mine()).isTrue();
    }

    @Test
    @DisplayName("남의 제보는 지울 수 없다")
    void cannotDeleteOthers() {
      var owner = signup("ac2", 1958, Set.of());
      var other = signup("ac3", 1990, Set.of());
      Long id = report(owner, true, List.of(), Set.of());
      em.flush();

      assertThatThrownBy(() -> reports.delete(other.getId(), id))
          .hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    @DisplayName("비로그인도 식당 제보를 볼 수 있다")
    void anonymousCanRead() {
      report(signup("ac4", 1958, Set.of("당뇨")), true, List.of(), Set.of());
      em.flush();

      var list = reports.listForRestaurant(null, restaurant.getId());
      assertThat(list.count()).isEqualTo(1);
      assertThat(list.reviews().get(0).mine()).isFalse();
      assertThat(list.reviews().get(0).author()).isNotEqualTo("내 후기");
    }
  }
}
