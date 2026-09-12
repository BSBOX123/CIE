-- 테스트용: V6 의 어휘 시드 부분만 (review 테이블 FK 를 피하기 위해).
-- 시드 내용을 복사하지 않도록 V6 에서 그대로 잘라 온 것이며, 값이 바뀌면
-- VocabularyServiceTest 가 잡는다.
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

