package com.meogeodo.user;

import com.meogeodo.security.SensitiveDataConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** 사용자 신체·표시 설정 (SPEC 4.2). */
@Entity
@Table(name = "user_profile")
public class UserProfile {

  @Id
  @Column(name = "user_id")
  private Long userId;

  @OneToOne
  @MapsId
  @JoinColumn(name = "user_id")
  private AppUser user;

  @Column(nullable = false, length = 50)
  private String name;

  @Column(length = 20)
  private String gender;

  @Column(name = "birth_year")
  private Short birthYear;

  @Column(name = "blood_type", length = 4)
  private String bloodType;

  @Column(name = "chewing_difficulty", nullable = false)
  private boolean chewingDifficulty;

  @Column(name = "med_note", columnDefinition = "text")
  @Convert(converter = SensitiveDataConverter.class)
  private String medNote;

  /**
   * 주문요청카드에 복용약을 인쇄할지.
   *
   * <p>카드는 매장에 건네는 물건이고 복용약은 민감정보다. 기본값은 꺼짐이며,
   * 사용자가 명시적으로 켠 경우에만 인쇄한다 (SPEC 11.4).
   */
  @Column(name = "show_meds_on_card", nullable = false)
  private boolean showMedsOnCard;

  /** 글자 크기 5단계 (SPEC 2.8). 고령 사용자가 주 대상이다. */
  @Column(name = "font_scale_idx", nullable = false)
  private short fontScaleIdx = 1;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt = OffsetDateTime.now();

  protected UserProfile() {}

  public UserProfile(String name) {
    this.name = name;
  }

  public Long getUserId() {
    return userId;
  }

  void setUser(AppUser user) {
    this.user = user;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getGender() {
    return gender;
  }

  public void setGender(String gender) {
    this.gender = gender;
  }

  public Short getBirthYear() {
    return birthYear;
  }

  public void setBirthYear(Short birthYear) {
    this.birthYear = birthYear;
  }

  public String getBloodType() {
    return bloodType;
  }

  public void setBloodType(String bloodType) {
    this.bloodType = bloodType;
  }

  public boolean isChewingDifficulty() {
    return chewingDifficulty;
  }

  public void setChewingDifficulty(boolean chewingDifficulty) {
    this.chewingDifficulty = chewingDifficulty;
  }

  public String getMedNote() {
    return medNote;
  }

  public void setMedNote(String medNote) {
    this.medNote = medNote;
  }

  public boolean isShowMedsOnCard() {
    return showMedsOnCard;
  }

  public void setShowMedsOnCard(boolean showMedsOnCard) {
    this.showMedsOnCard = showMedsOnCard;
  }

  public short getFontScaleIdx() {
    return fontScaleIdx;
  }

  public void setFontScaleIdx(short fontScaleIdx) {
    this.fontScaleIdx = fontScaleIdx;
  }

  public void touch() {
    this.updatedAt = OffsetDateTime.now();
  }
}
