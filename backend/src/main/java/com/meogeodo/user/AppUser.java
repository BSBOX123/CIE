package com.meogeodo.user;

import com.meogeodo.security.SensitiveDataConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Convert;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** 사용자 계정 (SPEC 4.2). */
@Entity
@Table(name = "app_user")
public class AppUser {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "login_id", nullable = false, unique = true, length = 50)
  private String loginId;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true,
      fetch = FetchType.LAZY)
  private UserProfile profile;

  // 질환·알레르기·복용약은 건강정보이며 개인정보보호법상 민감정보다.
  // 저장 시 암호화한다 (SPEC 11.4). 암호문 길이가 평문보다 길어 컬럼 길이를
  // 넉넉히 잡는다.
  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "user_disease", joinColumns = @JoinColumn(name = "user_id"))
  @Column(name = "code", nullable = false, length = 200)
  @Convert(converter = SensitiveDataConverter.class)
  private Set<String> diseases = new LinkedHashSet<>();

  /** 질환에서 자동 파생된 것과 사용자가 직접 고른 것을 구분한다 (SPEC 2.3). */
  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "user_care", joinColumns = @JoinColumn(name = "user_id"))
  private Set<CareSelection> cares = new LinkedHashSet<>();

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "user_allergy", joinColumns = @JoinColumn(name = "user_id"))
  @Column(name = "code", nullable = false, length = 200)
  @Convert(converter = SensitiveDataConverter.class)
  private Set<String> allergies = new LinkedHashSet<>();

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "user_medication", joinColumns = @JoinColumn(name = "user_id"))
  @Column(name = "code", nullable = false, length = 200)
  @Convert(converter = SensitiveDataConverter.class)
  private Set<String> medications = new LinkedHashSet<>();

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt = OffsetDateTime.now();

  protected AppUser() {}

  public AppUser(String loginId, String passwordHash) {
    this.loginId = loginId;
    this.passwordHash = passwordHash;
  }

  /** 판정에 쓰는 주의성분 값만. 자동/수동 구분은 화면 표시용이다. */
  public Set<String> careValues() {
    Set<String> out = new LinkedHashSet<>();
    cares.forEach(c -> out.add(c.getValue()));
    return out;
  }

  public void replaceCares(Set<CareSelection> next) {
    cares.clear();
    cares.addAll(next);
    touch();
  }

  public void replaceDiseases(Set<String> next) {
    diseases.clear();
    diseases.addAll(next);
    touch();
  }

  public void replaceAllergies(Set<String> next) {
    allergies.clear();
    allergies.addAll(next);
    touch();
  }

  public void replaceMedications(Set<String> next) {
    medications.clear();
    medications.addAll(next);
    touch();
  }

  public void touch() {
    this.updatedAt = OffsetDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public String getLoginId() {
    return loginId;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
    touch();
  }

  public UserProfile getProfile() {
    return profile;
  }

  public void setProfile(UserProfile profile) {
    this.profile = profile;
    if (profile != null) {
      profile.setUser(this);
    }
  }

  public Set<String> getDiseases() {
    return diseases;
  }

  public Set<CareSelection> getCares() {
    return cares;
  }

  public Set<String> getAllergies() {
    return allergies;
  }

  public Set<String> getMedications() {
    return medications;
  }
}
