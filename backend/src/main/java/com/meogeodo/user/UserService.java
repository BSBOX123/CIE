package com.meogeodo.user;

import com.meogeodo.vocabulary.VocabularyService;
import com.meogeodo.web.AuthDtos.CareView;
import com.meogeodo.web.AuthDtos.ProfileResponse;
import com.meogeodo.web.AuthDtos.ProfileUpdateRequest;
import com.meogeodo.web.AuthDtos.SignupRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원가입·프로필 (SPEC 9.2, 10.1). */
@Service
public class UserService {

  /** 판정 결과가 보이는 모든 응답에 붙인다 (SPEC 11.1). */
  public static final String DISCLAIMER =
      "참고 정보이며 의학적 조언이 아닙니다. 상태에 따라 담당 의료진과 상의하세요.";

  private final AppUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final VocabularyService vocabulary;

  public UserService(
      AppUserRepository users, PasswordEncoder passwordEncoder, VocabularyService vocabulary) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.vocabulary = vocabulary;
  }

  public static class DuplicateLoginIdException extends RuntimeException {
    public DuplicateLoginIdException(String loginId) {
      super("이미 사용 중인 아이디입니다: " + loginId);
    }
  }

  @Transactional
  public AppUser signup(SignupRequest request) {
    if (users.existsByLoginId(request.loginId())) {
      throw new DuplicateLoginIdException(request.loginId());
    }

    AppUser user =
        new AppUser(request.loginId(), passwordEncoder.encode(request.password()));

    UserProfile profile = new UserProfile(request.name());
    profile.setGender(request.gender());
    profile.setBirthYear(
        request.birthYear() == null ? null : request.birthYear().shortValue());
    profile.setBloodType(request.bloodType());
    profile.setChewingDifficulty(request.chewingDifficulty());
    profile.setMedNote(request.medNote());
    profile.setShowMedsOnCard(request.showMedsOnCard());
    user.setProfile(profile);

    Set<String> diseases = keepKnown(request.diseases(), vocabulary::isKnownDisease);
    user.replaceDiseases(diseases);
    user.replaceCares(resolveCares(diseases, request.cares()));
    user.replaceAllergies(keepKnown(request.allergies(), vocabulary::isKnownAllergen));
    user.replaceMedications(nonNull(request.medications()));

    return users.save(user);
  }

  /**
   * 질환에서 자동으로 따라오는 주의성분과 사용자가 직접 고른 것을 합친다.
   *
   * <p>자동 항목이 앞에 오고 {@code auto=true} 로 표시된다. 사용자가 자동
   * 항목을 뺄 수도 있어야 하므로, 요청에 명시적으로 담긴 값만 수동으로 본다.
   */
  private Set<CareSelection> resolveCares(Set<String> diseases, Set<String> requested) {
    Set<String> auto = vocabulary.autoCaresFor(diseases);
    Set<CareSelection> out = new LinkedHashSet<>();
    for (String care : auto) {
      out.add(new CareSelection(care, true));
    }
    for (String care : keepKnown(requested, vocabulary::isKnownCare)) {
      // CareSelection 의 동일성은 값으로만 판단하므로 자동 항목과 중복되지 않는다.
      out.add(new CareSelection(care, false));
    }
    return out;
  }

  /**
   * 프로필 조회.
   *
   * <p>사용자를 <b>트랜잭션 안에서</b> 읽는다. 컨트롤러가 먼저 조회해 넘기면
   * 엔티티가 준영속 상태가 되어, 지연 로딩 컬렉션(질환·주의성분 등)에 접근할
   * 때 터진다. 테스트는 {@code @Transactional} 이 전체를 감싸 이 문제가 드러나지
   * 않는다.
   */
  @Transactional(readOnly = true)
  public ProfileResponse viewById(Long userId) {
    return view(requireUser(userId));
  }

  @Transactional
  public ProfileResponse updateById(Long userId, ProfileUpdateRequest request) {
    return update(requireUser(userId), request);
  }

  private AppUser requireUser(Long userId) {
    if (userId == null) {
      throw new UnauthorizedException();
    }
    return users.findById(userId).orElseThrow(UnauthorizedException::new);
  }

  public static class UnauthorizedException extends RuntimeException {}

  @Transactional(readOnly = true)
  public ProfileResponse view(AppUser user) {
    UserProfile profile = user.getProfile();
    List<CareView> cares =
        user.getCares().stream()
            .map(c -> new CareView(
                c.getValue(), c.isAuto(), vocabulary.careNotes().get(c.getValue())))
            .toList();
    return new ProfileResponse(
        user.getLoginId(),
        profile == null ? null : profile.getName(),
        profile == null ? null : profile.getGender(),
        profile == null || profile.getBirthYear() == null
            ? null
            : profile.getBirthYear().intValue(),
        profile == null ? null : profile.getBloodType(),
        List.copyOf(user.getDiseases()),
        cares,
        List.copyOf(user.getAllergies()),
        profile != null && profile.isChewingDifficulty(),
        List.copyOf(user.getMedications()),
        profile == null ? null : profile.getMedNote(),
        profile != null && profile.isShowMedsOnCard(),
        profile == null ? 1 : profile.getFontScaleIdx(),
        DISCLAIMER);
  }

  @Transactional
  public ProfileResponse update(AppUser user, ProfileUpdateRequest request) {
    UserProfile profile = user.getProfile();
    if (profile == null) {
      profile = new UserProfile(request.name() == null ? "" : request.name());
      user.setProfile(profile);
    }
    if (request.name() != null) {
      profile.setName(request.name());
    }
    if (request.gender() != null) {
      profile.setGender(request.gender());
    }
    if (request.birthYear() != null) {
      profile.setBirthYear(request.birthYear().shortValue());
    }
    if (request.bloodType() != null) {
      profile.setBloodType(request.bloodType());
    }
    if (request.chewingDifficulty() != null) {
      profile.setChewingDifficulty(request.chewingDifficulty());
    }
    if (request.medNote() != null) {
      profile.setMedNote(request.medNote());
    }
    if (request.showMedsOnCard() != null) {
      profile.setShowMedsOnCard(request.showMedsOnCard());
    }
    if (request.fontScaleIdx() != null) {
      profile.setFontScaleIdx(request.fontScaleIdx().shortValue());
    }
    profile.touch();

    // 질환이 바뀌면 자동 주의성분도 다시 계산해야 한다.
    if (request.diseases() != null || request.cares() != null) {
      Set<String> diseases =
          request.diseases() == null
              ? user.getDiseases()
              : keepKnown(request.diseases(), vocabulary::isKnownDisease);
      Set<String> manual =
          request.cares() == null
              ? user.getCares().stream()
                  .filter(c -> !c.isAuto())
                  .map(CareSelection::getValue)
                  .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
              : request.cares();
      user.replaceDiseases(Set.copyOf(diseases));
      user.replaceCares(resolveCares(diseases, manual));
    }
    if (request.allergies() != null) {
      user.replaceAllergies(keepKnown(request.allergies(), vocabulary::isKnownAllergen));
    }
    if (request.medications() != null) {
      user.replaceMedications(request.medications());
    }
    user.touch();
    return view(users.save(user));
  }

  /** 어휘 정본에 없는 값은 버린다. 철자가 다르면 판정이 조용히 실패한다. */
  private Set<String> keepKnown(
      Set<String> values, java.util.function.Predicate<String> known) {
    Set<String> out = new LinkedHashSet<>();
    for (String value : nonNull(values)) {
      if (value != null && known.test(value.trim())) {
        out.add(value.trim());
      }
    }
    return out;
  }

  private static Set<String> nonNull(Set<String> values) {
    return values == null ? Set.of() : values;
  }
}
