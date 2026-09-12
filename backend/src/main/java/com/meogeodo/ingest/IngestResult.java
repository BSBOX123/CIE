package com.meogeodo.ingest;

/** 인제스트 실행 결과 요약. */
public record IngestResult(
    int fetched,
    int created,
    int updated,
    int skipped,
    int menusCreated,
    int dishesCreated,
    /** 공공데이터가 우리 스키마에 안 맞아 건너뛴 건수. */
    int failed) {

  public static IngestResult empty() {
    return new IngestResult(0, 0, 0, 0, 0, 0, 0);
  }

  public IngestResult plus(IngestResult other) {
    return new IngestResult(
        fetched + other.fetched,
        created + other.created,
        updated + other.updated,
        skipped + other.skipped,
        menusCreated + other.menusCreated,
        dishesCreated + other.dishesCreated,
        failed + other.failed);
  }

  @Override
  public String toString() {
    String base =
        "수집 %d건 (신규 %d, 갱신 %d, 변경없음 %d) / 메뉴 %d건 / 신규 음식 %d건"
            .formatted(fetched, created, updated, skipped, menusCreated, dishesCreated);
    return failed == 0 ? base : base + " / 실패 %d건".formatted(failed);
  }
}
