package com.meogeodo.user;

import com.meogeodo.security.SensitiveDataConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * 사용자가 고른 주의성분 한 건.
 *
 * <p>{@code auto} 는 질환에서 자동 파생되었는지를 나타낸다. 화면에서 " · 자동"
 * 을 붙여 보여주기 위한 것이며, 판정에는 영향이 없다 (SPEC 2.3).
 */
@Embeddable
public class CareSelection {

  // 주의성분은 질환에서 파생되므로 함께 민감정보로 다룬다 (SPEC 11.4).
  @Column(name = "code", nullable = false, length = 200)
  @Convert(converter = SensitiveDataConverter.class)
  private String value;

  @Column(name = "is_auto", nullable = false)
  private boolean auto;

  protected CareSelection() {}

  public CareSelection(String value, boolean auto) {
    this.value = value;
    this.auto = auto;
  }

  public String getValue() {
    return value;
  }

  public boolean isAuto() {
    return auto;
  }

  /** 동일성은 값으로만 판단한다. 같은 성분이 자동/수동으로 중복되면 안 된다. */
  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof CareSelection cs && Objects.equals(value, cs.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(value);
  }
}
