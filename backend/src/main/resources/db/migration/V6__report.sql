-- 제보 데이터 (MVP 4번, SPEC 6.5)
--
-- 방문한 사람이 남기는 한 건의 제보에는 두 가지가 들어간다.
--   ① 가게에 대한 평가 (요청이 받아들여졌는지, 자유 서술)
--   ② 자신의 주문 방법 (그때 어떤 요청을 했는지)
--
-- ②가 중요하다. 다음 사람이 "이 집에서는 이렇게 말하면 되는구나" 를 알 수 있고,
-- 이것이 restaurant_flag 를 채우는 유일한 경로다 — 무장애 API 는 음식점에
-- 대해 전 필드가 공란이었고, 알레르기 표기·저염 가능 여부는 어떤 공공 API 에도
-- 없다.

-- 제보자가 그때 실제로 사용한 요청 문구.
CREATE TABLE review_request (
    review_id  BIGINT NOT NULL REFERENCES review(id) ON DELETE CASCADE,
    sort_order INT NOT NULL,
    phrase     VARCHAR(120) NOT NULL,
    PRIMARY KEY (review_id, sort_order)
);

-- 피드백 문구 -> 식당 속성 매핑 (SPEC 6.5).
-- 후기가 쌓이면 이 매핑을 통해 restaurant_flag 가 채워진다.
CREATE TABLE vocabulary_feedback (
    phrase     VARCHAR(120) PRIMARY KEY,
    flag       VARCHAR(30),   -- NULL 이면 flag 로 이어지지 않는 단순 피드백
    positive   BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order SMALLINT NOT NULL
);

INSERT INTO vocabulary_feedback (phrase, flag, positive, sort_order) VALUES
  ('요청이 그대로 전달됐어요',      NULL,               TRUE,  1),
  ('간을 약하게 해 주셨어요',       '저염 요청 가능',      TRUE,  2),
  ('국물을 따로 담아 주셨어요',      '저염 요청 가능',      TRUE,  3),
  ('메뉴에 재료 표기가 있었어요',    '알레르기 표기 있음',   TRUE,  4),
  ('직원이 다시 확인해 주셨어요',    NULL,               TRUE,  5),
  ('입식 좌석이 있었어요',         '입식 좌석',          TRUE,  6),
  ('경사로가 있었어요',           '경사로',            TRUE,  7),
  ('요청을 전하기 어려웠어요',      NULL,               FALSE, 8);

-- 같은 사람이 같은 식당에 여러 번 제보할 수 있다(재방문). 다만 조회가 잦으므로
-- 인덱스를 둔다.
CREATE INDEX idx_review_user ON review (user_id, created_at DESC);
