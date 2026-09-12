package com.meogeodo.judgment;

import java.util.Collections;
import java.util.Set;

/**
 * 메뉴 1건에 붙은 주의성분·알레르기 태그.
 *
 * <p>알레르겐을 주재료({@code mainAllergens})와 양념 미량({@code traceAllergens})
 * 으로 나눠 담는다. 간장·된장이 들어간다는 이유까지 전부 같은 무게로 다루면
 * 밀 알레르기 사용자에게 대부분의 한국 음식이 금지되어 서비스가 무의미해진다
 * (SPEC 3.1).
 */
public record MenuTags(
    Set<String> cares, Set<String> mainAllergens, Set<String> traceAllergens) {

  public MenuTags {
    cares = freeze(cares);
    mainAllergens = freeze(mainAllergens);
    traceAllergens = freeze(traceAllergens);
  }

  /** 주재료 알레르겐만 있는 간단한 경우. */
  public static MenuTags of(Set<String> cares, Set<String> mainAllergens) {
    return new MenuTags(cares, mainAllergens, Set.of());
  }

  public static MenuTags empty() {
    return new MenuTags(Set.of(), Set.of(), Set.of());
  }

  private static Set<String> freeze(Set<String> values) {
    return values == null ? Set.of() : Collections.unmodifiableSet(Set.copyOf(values));
  }
}
