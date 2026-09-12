-- 테스트 전용 초기화.
--
-- 테스트 H2는 DB_CLOSE_DELAY=-1 로 JVM 내내 살아 있고, @MockitoBean 구성이
-- 다른 테스트마다 Spring 컨텍스트가 새로 뜬다. 그때마다 어휘 시드가 다시
-- 실행되므로 "이미 존재하는 테이블" 오류가 난다.
--
-- 시드 정본은 db/migration/V4__vocabulary.sql 하나뿐이며, 이 파일은 그 앞에서
-- 자리를 비워주기만 한다. 시드 내용을 여기에 복사하지 말 것.
DROP TABLE IF EXISTS vocabulary_feedback;
DROP TABLE IF EXISTS vocabulary_care_request;
DROP TABLE IF EXISTS vocabulary_request_phrase;
DROP TABLE IF EXISTS vocabulary_disease_care;
DROP TABLE IF EXISTS vocabulary_allergen;
DROP TABLE IF EXISTS vocabulary_care;
DROP TABLE IF EXISTS vocabulary_disease;
