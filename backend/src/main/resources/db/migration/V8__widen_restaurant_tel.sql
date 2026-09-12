-- V7 에서 다른 컬럼은 TEXT 로 바꾸면서 tel 만 VARCHAR(200) 으로 추측했는데,
-- 전국 적재 3,729건째에서 그 추측이 또 틀렸다.
--   Data truncation: Data too long for column 'tel'
-- 공공데이터의 전화번호 칸에는 매장별 번호가 줄줄이 들어오는 경우가 있다.
--
-- 인덱스가 걸려 있지 않은 표시 전용 컬럼이므로 TEXT 로 바꾼다.
ALTER TABLE restaurant MODIFY COLUMN tel TEXT;
