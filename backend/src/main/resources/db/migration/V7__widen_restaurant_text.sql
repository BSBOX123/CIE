-- 공공데이터의 식당 상세 텍스트가 예상보다 길다.
--
-- 강릉 100건으로 개발할 때는 안 걸렸으나, 전국 적재에서 터졌다:
--   Data truncation: Data too long for column 'open_time'
-- 한 건물에 여러 매장이 있는 경우 영업시간을 매장별로 나열해 200자를 넘는다.
--   예: "[아시안 레스토랑 동문]- 평일 10:30~17:00- 주말 ... [딴지펍]- 평일 ..."
--
-- 길이를 다시 추측하지 않고 TEXT 로 바꾼다. 검색·정렬에 쓰지 않는 표시 전용
-- 컬럼이라 인덱스가 필요 없다.
ALTER TABLE restaurant
    MODIFY COLUMN open_time   TEXT,
    MODIFY COLUMN rest_date   TEXT,
    MODIFY COLUMN parking     TEXT,
    MODIFY COLUMN packing     TEXT,
    MODIFY COLUMN reservation TEXT,
    -- 전화번호도 "0507-1341-5262 (예약: 063-...)" 처럼 여러 개가 들어온다.
    MODIFY COLUMN tel         VARCHAR(200);
