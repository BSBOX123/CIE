package com.meogeodo.tour;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * KorService2 {@code firstmenu}/{@code treatmenu} 자유 텍스트에서 메뉴를 뽑아낸다
 * (SPEC 7.1 [3] PARSE, [4] NORMALIZE).
 *
 * <p>공공데이터의 메뉴는 구조화되어 있지 않고 표기가 제각각이다. 실제 응답에서
 * 관찰된 형태:
 *
 * <pre>
 *   "메밀 부치기, 올챙이국수 등"                     쉼표 + 말미 '등'
 *   "감자옹심이/감자적/도토리들깨수제비"              슬래시, 공백 없음
 *   "소면/ 누릉지 등"                               슬래시 뒤에만 공백
 *   "들깨꼬소커피 / 메밀싹라떼 / 꽃청에이드 등"        슬래시 양쪽 공백
 *   "갈매기스페셜(모둠회+대게)중, ...대, ...특대"     괄호는 이름의 일부, 뒤는 크기
 * </pre>
 *
 * <p>괄호를 통째로 버리면 {@code 갈매기스페셜(모둠회+대게)} 가
 * {@code 갈매기스페셜} 이 되어 무엇인지 알 수 없게 되므로 보존한다. 반면 크기
 * 표기(대/중/소/특대)는 같은 음식의 다른 분량이므로 정규화 이름에서만 떼어내
 * 하나의 {@code dish} 로 모은다.
 */
@Component
public class MenuTextParser {

  /** 쉼표·슬래시·개행·가운뎃점을 모두 구분자로 본다. */
  private static final Pattern SEPARATOR = Pattern.compile("[,/·\\n\\r]+");

  /** 말미의 "등", "외" 같은 마무리 표현. 메뉴 이름이 아니다. */
  private static final Pattern TRAILING_ETC = Pattern.compile("\\s*(등|외|기타)\\s*$");

  /**
   * 크기 표기. 공백이나 닫는 괄호 뒤에 올 때만 크기로 본다.
   *
   * <p>이 조건이 없으면 {@code 전복죽}의 '죽'이나 {@code 모듬회}의 '회'처럼 이름
   * 끝 글자를 크기로 오인할 수 있다.
   */
  private static final Pattern SIZE_SUFFIX =
      Pattern.compile("(?<=[)\\s])\\s*(특대|대자|중자|소자|특|대|중|소)\\s*$");

  /** 가격 표기가 섞여 들어오는 경우. */
  private static final Pattern PRICE = Pattern.compile("\\s*[0-9][0-9,]*\\s*원?\\s*$");

  private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

  /** 흔한 오기. 같은 음식이 다른 dish로 갈라지는 것을 막는다. */
  private static final Map<String, String> SPELLING = Map.ofEntries(
      Map.entry("찌게", "찌개"),
      Map.entry("누릉지", "누룽지"),
      Map.entry("돈까스", "돈가스"),
      Map.entry("육계장", "육개장"),
      Map.entry("떡복이", "떡볶이"),
      Map.entry("떡뽁이", "떡볶이"),
      Map.entry("김치찌게", "김치찌개"),
      Map.entry("된장찌게", "된장찌개"));

  /** 메뉴로 볼 수 없는 값. */
  private static final int MAX_NAME_LENGTH = 60;

  /**
   * {@code firstmenu} 와 {@code treatmenu} 를 합쳐 메뉴 원문 목록을 만든다.
   *
   * @return 등장 순서를 보존한 중복 없는 목록. 첫 항목이 대표메뉴가 되도록
   *     {@code firstmenu} 를 앞에 둔다.
   */
  public List<String> parse(String firstMenu, String treatMenu) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    collectInto(out, firstMenu);
    collectInto(out, treatMenu);
    return List.copyOf(out);
  }

  private void collectInto(LinkedHashSet<String> out, String raw) {
    if (raw == null || raw.isBlank()) {
      return;
    }
    for (String piece : SEPARATOR.split(raw)) {
      String cleaned = cleanRawName(piece);
      if (isUsable(cleaned)) {
        out.add(cleaned);
      }
    }
  }

  /** 화면에 그대로 보여줄 메뉴 이름. 크기 표기는 남긴다. */
  public String cleanRawName(String piece) {
    String text = piece == null ? "" : piece.trim();
    text = TRAILING_ETC.matcher(text).replaceAll("");
    text = PRICE.matcher(text).replaceAll("");
    text = MULTI_SPACE.matcher(text).replaceAll(" ").trim();
    return text;
  }

  /**
   * {@code dish} 를 묶기 위한 정규화 이름.
   *
   * <p>크기 표기를 떼고 공백을 없앤다. {@code 계절메뉴스페셜 대} 와
   * {@code 계절메뉴스페셜 특대} 가 같은 음식으로 모여야 태깅을 한 번만 한다.
   */
  public String normalize(String rawName) {
    String text = cleanRawName(rawName);
    // 크기 표기가 겹쳐 붙는 경우가 있어 더 이상 줄지 않을 때까지 반복한다.
    String previous;
    do {
      previous = text;
      text = SIZE_SUFFIX.matcher(text).replaceAll("").trim();
    } while (!text.equals(previous));

    text = text.replace(" ", "");
    for (Map.Entry<String, String> fix : SPELLING.entrySet()) {
      text = text.replace(fix.getKey(), fix.getValue());
    }
    return text.toLowerCase(Locale.KOREAN);
  }

  /**
   * {@code 꼬막비빔밥+물회} 처럼 결합된 메뉴를 구성 음식으로 나눈다.
   *
   * <p>괄호 안의 {@code +} 는 재료 설명이므로 건드리지 않는다
   * ({@code 갈매기스페셜(모둠회+대게)}).
   */
  public List<String> splitCombo(String rawName) {
    String text = cleanRawName(rawName);
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    for (char c : text.toCharArray()) {
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth = Math.max(0, depth - 1);
      }
      if (c == '+' && depth == 0) {
        parts.add(current.toString().trim());
        current.setLength(0);
        continue;
      }
      current.append(c);
    }
    parts.add(current.toString().trim());
    return parts.stream().filter(this::isUsable).toList();
  }

  private boolean isUsable(String name) {
    if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
      return false;
    }
    // 한글이나 영문이 하나도 없으면 메뉴 이름이 아니다 (구분자 찌꺼기, 숫자 등)
    return name.codePoints().anyMatch(Character::isLetter);
  }
}
