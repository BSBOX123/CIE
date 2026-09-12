package com.meogeodo.judgment;

/**
 * 메뉴/식당 판정 결과 (SPEC 3장).
 *
 * <p>색만으로 판정을 전달하지 않는다. 기호와 텍스트 라벨을 항상 함께 노출한다
 * (SPEC 5.2 — 색각 이상 고려).
 */
public enum Verdict {
  /** 알레르기 유발물질이 들어 있다. */
  RED("✕", "알레르기 주의"),
  /** 주의 성분이 있어 조절이 필요하다. */
  INK("△", "조절하면 가능"),
  /** 그대로 주문해도 된다. */
  OK("○", "먹어도 돼요!");

  private final String symbol;
  private final String label;

  Verdict(String symbol, String label) {
    this.symbol = symbol;
    this.label = label;
  }

  public String symbol() {
    return symbol;
  }

  public String label() {
    return label;
  }
}
