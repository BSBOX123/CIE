package com.meogeodo.report;

import com.meogeodo.domain.RestaurantFlag;
import com.meogeodo.domain.RestaurantFlagRepository;
import com.meogeodo.domain.RestaurantRepository;
import com.meogeodo.domain.Review;
import com.meogeodo.domain.ReviewRepository;
import com.meogeodo.report.ReportDtos.CreateRequest;
import com.meogeodo.report.ReportDtos.ReviewListResponse;
import com.meogeodo.report.ReportDtos.ReviewView;
import com.meogeodo.user.AppUser;
import com.meogeodo.user.AppUserRepository;
import com.meogeodo.user.UserProfile;
import com.meogeodo.user.UserService;
import com.meogeodo.vocabulary.VocabularyService;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 제보 처리 (MVP 4번, SPEC 6.5).
 *
 * <p>제보가 쌓이면 {@code restaurant_flag} 가 채워진다. 이것이 알레르기 표기·
 * 저염 가능 여부를 아는 <b>유일한 경로</b>다 — 어떤 공공 API 에도 없고, 무장애
 * API 는 음식점에 대해 전 필드가 공란이었다.
 */
@Service
public class ReportService {

  private static final Logger log = LoggerFactory.getLogger(ReportService.class);

  /**
   * 같은 피드백이 이만큼 쌓이면 식당 속성으로 인정한다.
   *
   * <p>1건이면 한 사람의 경험일 뿐이다. 그 한 번이 우연이었을 수도 있고,
   * 그날 그 직원만 그랬을 수도 있다. 사용자가 이 표시를 보고 찾아가는 이상
   * 근거가 하나로는 부족하다.
   */
  static final int FLAG_THRESHOLD = 2;

  private final ReviewRepository reviews;
  private final RestaurantRepository restaurants;
  private final RestaurantFlagRepository flags;
  private final AppUserRepository users;
  private final VocabularyService vocabulary;

  public ReportService(
      ReviewRepository reviews,
      RestaurantRepository restaurants,
      RestaurantFlagRepository flags,
      AppUserRepository users,
      VocabularyService vocabulary) {
    this.reviews = reviews;
    this.restaurants = restaurants;
    this.flags = flags;
    this.users = users;
    this.vocabulary = vocabulary;
  }

  @Transactional
  public ReviewView create(Long userId, CreateRequest request) {
    AppUser user = requireUser(userId);
    if (!restaurants.existsById(request.restaurantId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "식당을 찾을 수 없습니다");
    }

    Review review = new Review(
        request.restaurantId(), userId, Boolean.TRUE.equals(request.ok()), request.note());
    review.setRequests(cleanRequests(request.requests()));
    // 정해진 문구만 받는다. 자유 입력을 허용하면 flag 도출이 무의미해진다.
    review.setFeedback(keepKnownFeedback(request.feedback()));
    reviews.save(review);

    deriveFlags(request.restaurantId());
    return toView(review, user, true);
  }

  @Transactional(readOnly = true)
  public ReviewListResponse listForRestaurant(Long viewerId, Long restaurantId) {
    List<Review> found = reviews.findByRestaurantIdOrderByCreatedAtDesc(restaurantId);
    String name = restaurantName(restaurantId);
    List<ReviewView> views = new ArrayList<>();
    for (Review r : found) {
      AppUser author = r.getUserId() == null ? null : users.findById(r.getUserId()).orElse(null);
      views.add(
          toView(r, author, r.getUserId() != null && r.getUserId().equals(viewerId), name));
    }
    List<String> derived = flags.findByRestaurantIdIn(List.of(restaurantId)).stream()
        .filter(f -> f.getSource() == RestaurantFlag.Source.COMMUNITY)
        .map(RestaurantFlag::getFlag)
        .toList();
    return new ReviewListResponse(restaurantId, views.size(), views, derived);
  }

  /** 내가 남긴 제보 = 내 방문 기록 (프론트 '내 정보' 화면). */
  @Transactional(readOnly = true)
  public List<ReviewView> listMine(Long userId) {
    AppUser user = requireUser(userId);
    List<Review> mine = reviews.findByUserIdOrderByCreatedAtDesc(userId);
    // 건마다 식당을 조회하면 기록이 쌓일수록 쿼리가 선형으로 늘어난다.
    Map<Long, String> names = new java.util.HashMap<>();
    restaurants.findAllById(mine.stream().map(Review::getRestaurantId).distinct().toList())
        .forEach(r -> names.put(r.getId(), r.getName()));
    return mine.stream()
        .map(r -> toView(r, user, true, names.get(r.getRestaurantId())))
        .toList();
  }

  @Transactional
  public void delete(Long userId, Long reviewId) {
    Review review = reviews.findByIdAndUserId(reviewId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "제보를 찾을 수 없습니다"));
    Long restaurantId = review.getRestaurantId();
    reviews.delete(review);
    // 지운 뒤 근거가 모자라면 flag 도 내려가야 한다.
    deriveFlags(restaurantId);
  }

  // ── flag 도출 (SPEC 6.5) ─────────────────────────────────────────

  /**
   * 이 식당의 제보를 모아 속성을 다시 계산한다.
   *
   * <p>제보가 지워질 수도 있으므로 <b>증분이 아니라 전체 재계산</b>이다. 식당당
   * 제보 수가 많지 않아 비용이 문제되지 않고, 증분 갱신은 삭제를 놓치기 쉽다.
   */
  void deriveFlags(Long restaurantId) {
    Map<String, String> mapping = vocabulary.feedbackFlags();

    Map<String, Integer> counts = new java.util.LinkedHashMap<>();
    for (Review r : reviews.findByRestaurantIdOrderByCreatedAtDesc(restaurantId)) {
      for (String phrase : r.getFeedback()) {
        String flag = mapping.get(phrase);
        if (flag != null) {
          counts.merge(flag, 1, Integer::sum);
        }
      }
    }

    Set<String> qualified = new LinkedHashSet<>();
    counts.forEach((flag, count) -> {
      if (count >= FLAG_THRESHOLD) {
        qualified.add(flag);
      }
    });

    List<RestaurantFlag> existing = flags.findByRestaurantIdIn(List.of(restaurantId));
    for (RestaurantFlag current : existing) {
      // 관리자가 넣은 값은 건드리지 않는다. 후기보다 우선한다.
      if (current.getSource() == RestaurantFlag.Source.COMMUNITY
          && !qualified.contains(current.getFlag())) {
        flags.delete(current);
        log.info("식당 {} 의 속성 '{}' 을(를) 내렸습니다 — 근거 부족",
            restaurantId, current.getFlag());
      }
    }
    Set<String> alreadyPresent = existing.stream()
        .map(RestaurantFlag::getFlag).collect(java.util.stream.Collectors.toSet());
    for (String flag : qualified) {
      if (!alreadyPresent.contains(flag)) {
        flags.save(new RestaurantFlag(restaurantId, flag, RestaurantFlag.Source.COMMUNITY));
        log.info("식당 {} 에 속성 '{}' 부여 — 제보 {}건", restaurantId, flag, counts.get(flag));
      }
    }
  }

  // ── 변환 ──────────────────────────────────────────────────────────

  private String restaurantName(Long restaurantId) {
    return restaurants.findById(restaurantId).map(r -> r.getName()).orElse(null);
  }

  private ReviewView toView(Review review, AppUser author, boolean mine) {
    return toView(review, author, mine, restaurantName(review.getRestaurantId()));
  }

  /** 식당 이름을 이미 알고 있을 때. 목록에서 건마다 조회하지 않기 위함이다. */
  private ReviewView toView(
      Review review, AppUser author, boolean mine, String restaurantName) {
    return new ReviewView(
        review.getId(),
        review.getRestaurantId(),
        restaurantName,
        mine ? "내 후기" : authorLabel(author),
        mine,
        review.getCreatedAt().toLocalDate(),
        review.isOk(),
        review.isOk() ? "○" : "✕",
        review.getNote(),
        List.copyOf(review.getRequests()),
        List.copyOf(review.getFeedback()));
  }

  /**
   * 표시용 작성자 이름.
   *
   * <p>{@code "60대 · 당뇨"} 처럼 뭉뚱그린다. 이름도, 정확한 나이도, 질환 전체도
   * 노출하지 않는다 — 건강정보이기 때문이다 (SPEC 11.4).
   */
  static String authorLabel(AppUser author) {
    if (author == null) {
      return "익명";
    }
    List<String> parts = new ArrayList<>();
    UserProfile profile = author.getProfile();
    if (profile != null && profile.getBirthYear() != null) {
      int age = Year.now().getValue() - profile.getBirthYear() + 1;
      if (age >= 10 && age < 120) {
        parts.add((age / 10) * 10 + "대");
      }
    }
    // 질환은 대표 하나만. 전부 나열하면 특정 개인을 좁힐 수 있다.
    author.getDiseases().stream().findFirst().ifPresent(parts::add);
    return parts.isEmpty() ? "익명" : String.join(" · ", parts);
  }

  private List<String> cleanRequests(List<String> requests) {
    if (requests == null) {
      return List.of();
    }
    return requests.stream()
        .filter(p -> p != null && !p.isBlank())
        .map(String::trim)
        .distinct()
        .limit(20)
        .toList();
  }

  private Set<String> keepKnownFeedback(Set<String> feedback) {
    if (feedback == null) {
      return Set.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String phrase : feedback) {
      if (phrase != null && vocabulary.isKnownFeedback(phrase.trim())) {
        out.add(phrase.trim());
      }
    }
    return out;
  }

  private AppUser requireUser(Long userId) {
    if (userId == null) {
      throw new UserService.UnauthorizedException();
    }
    return users.findById(userId).orElseThrow(UserService.UnauthorizedException::new);
  }
}
