-- 태깅 실행 기록 (SPEC 7.1 [5] TAG)
--
-- Google AI Studio 무료 티어에는 Batch API가 없어(2026-09 확인) 태깅은 동기
-- 방식으로 돈다. 비동기 배치를 추적할 필요는 없지만, 언제 몇 건을 처리했고
-- 몇 건이 실패했는지는 남겨야 한다. 무료 티어의 일일 한도 때문에 여러 날에
-- 걸쳐 나눠 돌게 되므로 진행 상황을 볼 수 있어야 한다.

CREATE TABLE tagging_run (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    requested     INTEGER NOT NULL,
    tagged        INTEGER NOT NULL DEFAULT 0,
    failed        INTEGER NOT NULL DEFAULT 0,
    model_id      VARCHAR(50),
    started_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at   DATETIME(6),
    note          VARCHAR(500)
);
CREATE INDEX idx_tagging_run_recent ON tagging_run (started_at DESC);
