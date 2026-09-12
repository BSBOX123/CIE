package com.meogeodo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** KorService2에서 수집한 음식점 (SPEC 4.2). */
@Entity
@Table(name = "restaurant")
public class Restaurant {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** KorService2 {@code contentid}. 증분 인제스트의 기준키. */
  @Column(name = "content_id", nullable = false, unique = true, length = 20)
  private String contentId;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(length = 200)
  private String area;

  @Column(length = 300)
  private String addr1;

  @Column(length = 200)
  private String addr2;

  @Column(name = "area_code")
  private Short areaCode;

  @Column(name = "sigungu_code")
  private Short sigunguCode;

  /** KorService2 {@code mapy}. */
  @Column(precision = 10, scale = 7)
  private BigDecimal lat;

  /** KorService2 {@code mapx}. */
  @Column(precision = 10, scale = 7)
  private BigDecimal lng;

  @Column(length = 200)
  private String tel;

  @Column(name = "open_time", columnDefinition = "text")
  private String openTime;

  @Column(name = "rest_date", columnDefinition = "text")
  private String restDate;

  @Column(columnDefinition = "text")
  private String parking;

  @Column(columnDefinition = "text")
  private String packing;

  @Column(columnDefinition = "text")
  private String reservation;

  @Column(name = "first_image", length = 500)
  private String firstImage;

  /** firstmenu + treatmenu 원문. 파싱 규칙이 바뀌어도 재처리할 수 있게 보존한다. */
  @Column(name = "raw_menu_text", columnDefinition = "text")
  private String rawMenuText;

  /** KorService2 {@code modifiedtime}. 변경분만 다시 받기 위해 저장한다. */
  @Column(name = "source_modified_at", length = 20)
  private String sourceModifiedAt;

  @Column(name = "ingested_at", nullable = false)
  private OffsetDateTime ingestedAt = OffsetDateTime.now();

  protected Restaurant() {}

  public Restaurant(String contentId, String name) {
    this.contentId = contentId;
    this.name = name;
  }

  public Long getId() {
    return id;
  }

  public String getContentId() {
    return contentId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getArea() {
    return area;
  }

  public void setArea(String area) {
    this.area = area;
  }

  public String getAddr1() {
    return addr1;
  }

  public void setAddr1(String addr1) {
    this.addr1 = addr1;
  }

  public void setAddr2(String addr2) {
    this.addr2 = addr2;
  }

  public Short getAreaCode() {
    return areaCode;
  }

  public void setAreaCode(Short areaCode) {
    this.areaCode = areaCode;
  }

  public Short getSigunguCode() {
    return sigunguCode;
  }

  public void setSigunguCode(Short sigunguCode) {
    this.sigunguCode = sigunguCode;
  }

  public BigDecimal getLat() {
    return lat;
  }

  public void setLat(BigDecimal lat) {
    this.lat = lat;
  }

  public BigDecimal getLng() {
    return lng;
  }

  public void setLng(BigDecimal lng) {
    this.lng = lng;
  }

  public String getTel() {
    return tel;
  }

  public void setTel(String tel) {
    this.tel = tel;
  }

  public String getOpenTime() {
    return openTime;
  }

  public void setOpenTime(String openTime) {
    this.openTime = openTime;
  }

  public String getRestDate() {
    return restDate;
  }

  public void setRestDate(String restDate) {
    this.restDate = restDate;
  }

  public String getParking() {
    return parking;
  }

  public void setParking(String parking) {
    this.parking = parking;
  }

  public String getPacking() {
    return packing;
  }

  public void setPacking(String packing) {
    this.packing = packing;
  }

  public String getReservation() {
    return reservation;
  }

  public void setReservation(String reservation) {
    this.reservation = reservation;
  }

  public String getFirstImage() {
    return firstImage;
  }

  public void setFirstImage(String firstImage) {
    this.firstImage = firstImage;
  }

  public String getRawMenuText() {
    return rawMenuText;
  }

  public void setRawMenuText(String rawMenuText) {
    this.rawMenuText = rawMenuText;
  }

  public String getSourceModifiedAt() {
    return sourceModifiedAt;
  }

  public void setSourceModifiedAt(String sourceModifiedAt) {
    this.sourceModifiedAt = sourceModifiedAt;
  }

  public void setIngestedAt(OffsetDateTime ingestedAt) {
    this.ingestedAt = ingestedAt;
  }
}
