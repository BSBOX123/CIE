package com.meogeodo.ingest;

/** 인제스트 실행 결과 요약. */
public record IngestResult(
    int fetched, int created, int updated, int skipped, int menusCreated, int dishesCreated) {

  public static IngestResult empty() {
    return new IngestResult(0, 0, 0, 0, 0, 0);
  }

  public IngestResult plus(IngestResult other) {
    return new IngestResult(
        fetched + other.fetched,
        created + other.created,
        updated + other.updated,
        skipped + other.skipped,
        menusCreated + other.menusCreated,
        dishesCreated + other.dishesCreated);
  }

  @Override
  public String toString() {
    return "수집 %d건 (신규 %d, 갱신 %d, 변경없음 %d) / 메뉴 %d건 / 신규 음식 %d건"
        .formatted(fetched, created, updated, skipped, menusCreated, dishesCreated);
  }
}
