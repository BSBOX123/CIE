package com.meogeodo.judgment;

import java.util.Collections;
import java.util.Set;

/**
 * 판정에 쓰이는 사용자 정보만 담는다.
 *
 * <p>혈액형·복용약은 판정에 반영하지 않는다 (SPEC 12 D14). 카드 표시용으로만
 * 수집하며, 여기 들어오지 않는다.
 */
public record UserHealthProfile(Set<String> cares, Set<String> allergies) {

  public UserHealthProfile {
    cares = cares == null ? Set.of() : Collections.unmodifiableSet(Set.copyOf(cares));
    allergies =
        allergies == null ? Set.of() : Collections.unmodifiableSet(Set.copyOf(allergies));
  }

  public static UserHealthProfile empty() {
    return new UserHealthProfile(Set.of(), Set.of());
  }
}
