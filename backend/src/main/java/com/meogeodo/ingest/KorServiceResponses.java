package com.meogeodo.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KorService2 응답 매핑.
 *
 * <p>응답이 {@code response.body.items.item} 으로 세 겹 중첩되고, 결과가 없으면
 * {@code items} 가 객체가 아니라 빈 문자열({@code ""})로 오는 경우가 있어
 * 방어적으로 다룬다.
 */
public final class KorServiceResponses {

  private KorServiceResponses() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Envelope(Response response) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Response(Header header, Body body) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Header(String resultCode, String resultMsg) {
    public boolean isSuccess() {
      return "0000".equals(resultCode) || "00".equals(resultCode);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Body(Items items, Integer numOfRows, Integer pageNo, Integer totalCount) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Items(List<Item> item) {
    public List<Item> safeItems() {
      return item == null ? List.of() : item;
    }
  }

  /** 목록/상세 응답을 한 타입으로 받는다. 필드는 요청 종류에 따라 비어 있을 수 있다. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Item(
      String contentid,
      String contenttypeid,
      String title,
      String addr1,
      String addr2,
      String areacode,
      String sigungucode,
      String mapx,
      String mapy,
      String tel,
      String firstimage,
      String modifiedtime,
      String dist,
      // detailIntro2 전용
      String firstmenu,
      String treatmenu,
      String infocenterfood,
      String opentimefood,
      String restdatefood,
      String parkingfood,
      String packing,
      String reservationfood) {}
}
