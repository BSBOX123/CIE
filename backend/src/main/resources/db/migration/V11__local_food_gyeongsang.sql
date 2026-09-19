-- 지역 음식: 경상권 13종 (SPEC 7.5, MVP 범위는 경상도).
--
-- 우리가 고르고 쓴 큐레이션이라 DB 에 둔다. 이 음식을 파는 식당은 저장하지 않고
-- 요청 때마다 관광공사에서 keywords 로 찾는다 (공모전 규정: 공사 데이터 적재 금지).
-- 그래서 restaurant_local_food 는 쓰지 않는다.
--
-- 지역은 관광공사가 주는 법정동 시도 코드(lDongRegnCd)로 맞춘다:
--   26 부산 · 27 대구 · 31 울산 · 47 경북 · 48 경남
-- V1 의 area_code/sigungu_code 는 옛 관광공사 지역코드 체계라 쓰지 않는다.
--
-- 설명·팁은 초안이다(verified = FALSE). 팀 검수 후 TRUE 로 바꾼다.
-- 후보 검토 문서: 과메기(데이터 없음)·고래고기(부적절) 제외, 동래파전 보류.

ALTER TABLE local_food
    ADD COLUMN ldong_regn_cd VARCHAR(5),
    ADD COLUMN keywords      VARCHAR(200),
    ADD COLUMN sort_order    INT NOT NULL DEFAULT 0;
CREATE INDEX idx_local_food_ldong ON local_food (ldong_regn_cd);

-- 판정은 음식 사전(dish)의 태그로 한다. 사전에 없는 이름은 등록해 태깅 큐에 넣는다.
INSERT IGNORE INTO dish (normalized_name) VALUES
    ('밀면'), ('돼지국밥'), ('찜갈비'), ('막창구이'), ('언양불고기'), ('물회'),
    ('헛제사밥'), ('안동찜닭'), ('어탕국수'), ('추어탕'), ('아구찜'), ('재첩국'), ('충무김밥');

INSERT INTO local_food
    (id, name, dish_id, ldong_regn_cd, region_label, description, keywords, sort_order, verified)
VALUES
    ('milmyeon', '밀면', (SELECT id FROM dish WHERE normalized_name = '밀면'), '26', '부산',
     '밀가루 면에 사골과 한약재를 우린 육수를 부어 먹는 부산식 냉면. 6·25 때 메밀을 구하기 어려워 밀가루로 대신한 데서 시작했습니다.',
     '밀면', 10, FALSE),
    ('dwaeji-gukbap', '돼지국밥', (SELECT id FROM dish WHERE normalized_name = '돼지국밥'), '26', '부산',
     '돼지 뼈와 고기를 오래 우린 국물에 밥을 맙니다. 새우젓으로 간을 맞추고 부추무침을 올립니다.',
     '돼지국밥', 20, FALSE),
    ('jjimgalbi', '동인동 찜갈비', (SELECT id FROM dish WHERE normalized_name = '찜갈비'), '27', '대구',
     '마늘과 고춧가루를 듬뿍 넣어 맵게 조린 갈비. 대구 동인동 골목에서 시작해 양은 냄비째 나옵니다.',
     '찜갈비', 30, FALSE),
    ('makchang', '막창구이', (SELECT id FROM dish WHERE normalized_name = '막창구이'), '27', '대구',
     '돼지 막창을 구워 된장 소스에 찍어 먹습니다. 대구에서는 소금보다 된장 양념을 씁니다.',
     '막창', 40, FALSE),
    ('eonyang-bulgogi', '언양불고기', (SELECT id FROM dish WHERE normalized_name = '언양불고기'), '31', '울산',
     '얇게 썬 소고기를 양념해 석쇠에 구워 냅니다. 국물 없이 굽는 것이 서울식과 다릅니다.',
     '언양불고기,언양석쇠불고기', 50, FALSE),
    ('mulhoe', '물회', (SELECT id FROM dish WHERE normalized_name = '물회'), '47', '경북 포항',
     '흰살생선과 오징어를 채썬 채소와 함께 초고추장 국물에 말아 먹습니다. 포항에서는 육수를 부어 밥을 말아 먹기도 합니다.',
     '물회', 60, FALSE),
    ('andong-jjimdak', '안동찜닭', (SELECT id FROM dish WHERE normalized_name = '안동찜닭'), '47', '경북 안동',
     '닭과 감자·당면을 간장 양념에 졸여 냅니다. 안동 구시장 골목에서 시작했습니다.',
     '찜닭', 70, FALSE),
    ('heotjesabap', '헛제사밥', (SELECT id FROM dish WHERE normalized_name = '헛제사밥'), '47', '경북 안동',
     '제사 음식을 평상시에 차려 먹던 데서 온 비빔밥. 고추장 대신 간장으로 비비고 탕국을 곁들입니다.',
     '헛제사밥', 80, FALSE),
    ('eotang-guksu', '어탕국수', (SELECT id FROM dish WHERE normalized_name = '어탕국수'), '48', '경남',
     '민물고기를 통째로 갈아 끓인 국물에 국수를 말아 먹습니다. 산청·함양 일대에서 즐겨 먹습니다.',
     '어탕', 90, FALSE),
    ('chueotang', '추어탕', (SELECT id FROM dish WHERE normalized_name = '추어탕'), '48', '경남',
     '미꾸라지를 갈아 된장을 풀고 시래기를 넣어 끓입니다. 경상도식은 국물을 걸쭉하게 내는 편입니다.',
     '추어탕', 100, FALSE),
    ('agujjim', '아구찜', (SELECT id FROM dish WHERE normalized_name = '아구찜'), '48', '경남 마산',
     '아귀와 콩나물을 고춧가루 양념에 쪄 냅니다. 마산에서는 말린 아귀를 씁니다.',
     '아구찜,아귀찜', 110, FALSE),
    ('jaecheopguk', '재첩국', (SELECT id FROM dish WHERE normalized_name = '재첩국'), '48', '경남 하동',
     '섬진강 재첩을 맑게 끓여 부추를 얹습니다. 간을 세게 하지 않는 것이 특징입니다.',
     '재첩', 120, FALSE),
    ('chungmu-gimbap', '충무김밥', (SELECT id FROM dish WHERE normalized_name = '충무김밥'), '48', '경남 통영',
     '밥만 김에 말고 무김치와 오징어무침을 따로 냅니다. 통영의 옛 이름 충무에서 이름이 왔습니다.',
     '충무김밥', 130, FALSE);

INSERT INTO local_food_tip (food_id, phrase, sort_order) VALUES
    ('milmyeon', '육수는 조금만 담아 주세요', 1),
    ('milmyeon', '양념장은 따로 주세요', 2),
    ('dwaeji-gukbap', '새우젓은 빼 주세요', 1),
    ('dwaeji-gukbap', '국물은 조금만 주세요', 2),
    ('jjimgalbi', '덜 맵게 해 주세요', 1),
    ('jjimgalbi', '양념은 따로 주세요', 2),
    ('makchang', '된장 소스는 따로 주세요', 1),
    ('eonyang-bulgogi', '양념은 연하게 해 주세요', 1),
    ('mulhoe', '초고추장은 따로 주세요', 1),
    ('mulhoe', '국물은 조금만 주세요', 2),
    ('andong-jjimdak', '덜 짜게 해 주세요', 1),
    ('andong-jjimdak', '당면은 조금만 주세요', 2),
    ('heotjesabap', '간장은 조금만 주세요', 1),
    ('heotjesabap', '탕국은 따로 주세요', 2),
    ('eotang-guksu', '국물은 조금만 담아 주세요', 1),
    ('eotang-guksu', '제피(산초)는 빼 주세요', 2),
    ('chueotang', '국물은 조금만 담아 주세요', 1),
    ('chueotang', '들깨는 빼 주세요', 2),
    ('agujjim', '덜 맵게 해 주세요', 1),
    ('agujjim', '콩나물 많이 주세요', 2),
    ('jaecheopguk', '소금은 따로 주세요', 1),
    ('chungmu-gimbap', '오징어무침은 따로 주세요', 1);
