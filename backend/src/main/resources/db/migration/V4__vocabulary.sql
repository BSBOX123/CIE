-- 고정 어휘 정본 (SPEC 2장)
--
-- 'value'는 H2 등에서 예약어라 컬럼명으로 code 를 쓴다.
-- 질환/주의성분/알레르기 문자열은 백엔드·ai-service·프론트가 모두 써야 하고,
-- 철자가 한 글자만 달라도 판정이 조용히 실패한다(교집합이 빈 집합이 됨).
-- DB를 정본으로 두고 백엔드가 API로 노출한다.

CREATE TABLE vocabulary_disease (
    code       VARCHAR(30) PRIMARY KEY,
    sort_order SMALLINT NOT NULL
);

CREATE TABLE vocabulary_care (
    code       VARCHAR(30) PRIMARY KEY,
    note       VARCHAR(200) NOT NULL,   -- 사용자 안내 문구
    sort_order SMALLINT NOT NULL
);

CREATE TABLE vocabulary_allergen (
    code       VARCHAR(30) PRIMARY KEY,
    sort_order SMALLINT NOT NULL
);

-- 질환 -> 자동 선택되는 주의성분 (SPEC 2.3)
CREATE TABLE vocabulary_disease_care (
    disease VARCHAR(30) NOT NULL REFERENCES vocabulary_disease(code),
    care    VARCHAR(30) NOT NULL REFERENCES vocabulary_care(code),
    PRIMARY KEY (disease, care)
);

-- 기본 요청 문구 (SPEC 2.5)
CREATE TABLE vocabulary_request_phrase (
    phrase     VARCHAR(120) PRIMARY KEY,
    sort_order SMALLINT NOT NULL
);

-- 주의성분 -> 권장 요청 문구 (SPEC 2.6)
CREATE TABLE vocabulary_care_request (
    care       VARCHAR(30)  NOT NULL REFERENCES vocabulary_care(code),
    phrase     VARCHAR(120) NOT NULL,
    sort_order SMALLINT NOT NULL,
    PRIMARY KEY (care, phrase)
);

INSERT INTO vocabulary_disease (code, sort_order) VALUES
  ('당뇨',1),('고혈압',2),('이상지질혈증',3),('만성콩팥병',4),('통풍',5);

INSERT INTO vocabulary_care (code, note, sort_order) VALUES
  ('나트륨',       '국물∙양념∙젓갈에 몰려 있어요', 1),
  ('당류',         '초고추장∙조림장에 설탕이 들어가요', 2),
  ('정제 탄수화물', '흰밥∙면 양이 혈당을 좌우해요', 3),
  ('포화지방',     '껍질∙비계∙진한 국물에 많아요', 4),
  ('칼륨',         '채소∙해조류를 데치면 줄어요', 5),
  ('퓨린',         '진한 육수와 내장∙등푸른 생선에 많아요', 6),
  ('단백질량',     '한 끼에 들어가는 고기∙생선∙두부 양으로 조절해요', 7);

-- 식약처 알레르기 유발물질 표시 대상 19종 (D11 확장 반영)
INSERT INTO vocabulary_allergen (code, sort_order) VALUES
  ('난류',1),('우유',2),('메밀',3),('땅콩',4),('대두',5),('밀',6),('고등어',7),
  ('게',8),('새우',9),('돼지고기',10),('복숭아',11),('토마토',12),('호두',13),
  ('닭고기',14),('쇠고기',15),('오징어',16),('조개류',17),('아황산류',18),('잣',19);

INSERT INTO vocabulary_disease_care (disease, care) VALUES
  ('당뇨','당류'),('당뇨','정제 탄수화물'),
  ('고혈압','나트륨'),('고혈압','포화지방'),
  ('이상지질혈증','정제 탄수화물'),('이상지질혈증','포화지방'),
  ('만성콩팥병','나트륨'),('만성콩팥병','칼륨'),('만성콩팥병','단백질량'),
  ('통풍','나트륨'),('통풍','퓨린');

INSERT INTO vocabulary_request_phrase (phrase, sort_order) VALUES
  ('국물은 따로 담아 주세요',1),
  ('소금·간장은 반만 넣어 주세요',2),
  ('양념은 따로 주세요',3),
  ('설탕은 넣지 말아 주세요',4),
  ('밥은 반만 주세요',5),
  ('잘게 썰어 주세요',6);

INSERT INTO vocabulary_care_request (care, phrase, sort_order) VALUES
  ('나트륨','국물은 따로 담아 주세요',1),
  ('나트륨','소금·간장은 반만 넣어 주세요',2),
  ('당류','설탕은 넣지 말아 주세요',1),
  ('당류','양념은 따로 주세요',2),
  ('정제 탄수화물','밥은 반만 주세요',1),
  ('정제 탄수화물','면은 반만 주세요',2),
  ('포화지방','껍질·비계는 빼 주세요',1),
  ('포화지방','국물은 따로 담아 주세요',2),
  ('칼륨','채소는 데쳐 주세요',1),
  ('퓨린','국물은 따로 담아 주세요',1),
  ('단백질량','고기·생선은 절반만 주세요',1);
