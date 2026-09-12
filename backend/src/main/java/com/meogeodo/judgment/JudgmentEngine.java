package com.meogeodo.judgment;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 먹어도 되는지 판정한다 (SPEC 3장).
 *
 * <p><b>이 클래스는 결정론적이어야 한다.</b> 지병 관련 판단이므로 같은 사용자와
 * 같은 메뉴에 대해 언제나 같은 결과가 나와야 하고, 근거를 추적할 수 있어야 한다.
 * LLM은 여기 관여하지 않는다 — 태그를 만드는 데까지만 쓰인다 (SPEC 8.2).
 *
 * <p><b>메뉴와 식당의 판정 방향이 반대다.</b> 메뉴는 비관적으로(하나라도 걸리면
 * 위험), 식당은 낙관적으로(안전한 메뉴가 하나라도 있으면 갈 만함) 판정한다.
 */
@Component
public class JudgmentEngine {

  /**
   * 메뉴 판정.
   *
   * <p>알레르겐이 <b>주재료</b>로 들어가면 RED다. 빼달라고 할 수 없기 때문이다.
   * 반면 양념에 미량 들어가는 경우는 INK로 둔다 — 이 앱의 존재 이유가 "빼고
   * 먹기"이고, 양념은 실제로 뺄 수 있다. 다만 화면에서 반드시 "매장에 확인"을
   * 함께 알려야 한다 (SPEC 11.1).
   */
  public Verdict judgeMenu(MenuTags tags, UserHealthProfile profile) {
    if (intersects(tags.mainAllergens(), profile.allergies())) {
      return Verdict.RED;
    }
    if (intersects(tags.traceAllergens(), profile.allergies())) {
      return Verdict.INK;
    }
    if (intersects(tags.cares(), profile.cares())) {
      return Verdict.INK;
    }
    return Verdict.OK;
  }

  /**
   * 식당 판정. 가장 안전한 메뉴 하나를 대표로 삼는다.
   *
   * <p>메뉴가 하나도 없으면 판정할 근거가 없다. 이때 OK로 답하면 "안전하다"는
   * 잘못된 신호가 되므로 {@code null} 을 돌려주고 화면에서 판정을 숨긴다.
   */
  public Verdict judgeRestaurant(Collection<MenuTags> menus, UserHealthProfile profile) {
    if (menus == null || menus.isEmpty()) {
      return null;
    }
    boolean hasInk = false;
    for (MenuTags menu : menus) {
      Verdict verdict = judgeMenu(menu, profile);
      if (verdict == Verdict.OK) {
        return Verdict.OK;
      }
      if (verdict == Verdict.INK) {
        hasInk = true;
      }
    }
    return hasInk ? Verdict.INK : Verdict.RED;
  }

  /** 사용자에게 문제가 되는 주재료 알레르겐. 이 메뉴는 피해야 한다. */
  public Set<String> hitMainAllergens(MenuTags tags, UserHealthProfile profile) {
    return intersection(tags.mainAllergens(), profile.allergies());
  }

  /** 사용자에게 문제가 되는 양념 미량 알레르겐. 빼달라고 요청할 여지가 있다. */
  public Set<String> hitTraceAllergens(MenuTags tags, UserHealthProfile profile) {
    return intersection(tags.traceAllergens(), profile.allergies());
  }

  /** 사용자에게 실제로 문제가 되는 주의성분만 추린다. */
  public Set<String> hitCares(MenuTags tags, UserHealthProfile profile) {
    return intersection(tags.cares(), profile.cares());
  }

  /** 지역 음식 목록의 요약 문구 (SPEC 3.3). */
  public String summarize(MenuTags tags, UserHealthProfile profile) {
    List<String> mainHits = List.copyOf(hitMainAllergens(tags, profile));
    if (!mainHits.isEmpty()) {
      return suffix(mainHits, "있음");
    }
    List<String> traceHits = List.copyOf(hitTraceAllergens(tags, profile));
    if (!traceHits.isEmpty()) {
      return suffix(traceHits, "양념에 있음");
    }
    List<String> careHits = List.copyOf(hitCares(tags, profile));
    if (!careHits.isEmpty()) {
      return suffix(careHits, "조절 필요");
    }
    return "조절 불필요";
  }

  private String suffix(List<String> hits, String tail) {
    if (hits.size() == 1) {
      return hits.get(0) + " " + tail;
    }
    return hits.get(0) + " 외 " + (hits.size() - 1) + " " + tail;
  }

  private boolean intersects(Set<String> left, Set<String> right) {
    if (left.isEmpty() || right.isEmpty()) {
      return false;
    }
    Set<String> smaller = left.size() <= right.size() ? left : right;
    Set<String> larger = smaller == left ? right : left;
    for (String value : smaller) {
      if (larger.contains(value)) {
        return true;
      }
    }
    return false;
  }

  private Set<String> intersection(Set<String> tags, Set<String> userValues) {
    Set<String> hits = new LinkedHashSet<>();
    for (String tag : tags) {
      if (userValues.contains(tag)) {
        hits.add(tag);
      }
    }
    return hits;
  }
}
