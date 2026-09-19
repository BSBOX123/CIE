-- 지역 음식을 시도별로 보충한다.
--
-- 목록이 "내 시도 음식만" 보여 주도록 바뀌면서 (예전엔 경상권 전체를 섞어 대구에서도
-- 부산 밀면이 떴다) 대구 2종·부산 2종처럼 얇은 시도가 생겼다. 관광공사 상호 검색으로
-- 그 시도 안에 실제로 파는 가게가 있는 음식만 골랐다 (2026-09-20 실측, 괄호 안은 가게 수):
--   부산 곰장어(7) · 대구 납작만두(1)·따로국밥(1) · 경북 대게(6)·쌈밥(9) · 경남 진주냉면(2)
-- 이름에 없어도 메뉴에 있는 가게는 요청 때 근처 식당 메뉴를 열어 더 찾는다.
--
-- 울산은 언양불고기 1종뿐이다. 고래고기는 제외했고 그 밖에 가게가 잡히는 향토 음식이 없었다.
-- 설명·팁은 초안이다(verified = FALSE).

INSERT IGNORE INTO dish (normalized_name) VALUES
    ('곰장어구이'), ('납작만두'), ('따로국밥'), ('영덕대게'), ('쌈밥'), ('진주냉면');

INSERT INTO local_food
    (id, name, dish_id, ldong_regn_cd, region_label, description, keywords, sort_order, verified)
VALUES
    ('gomjangeo', '곰장어구이', (SELECT id FROM dish WHERE normalized_name = '곰장어구이'), '26', '부산',
     '곰장어(먹장어)를 손질해 연탄불에 굽거나 양념해 볶아 먹습니다. 자갈치시장 일대가 유명합니다.',
     '곰장어,꼼장어', 25, FALSE),
    ('napjak-mandu', '납작만두', (SELECT id FROM dish WHERE normalized_name = '납작만두'), '27', '대구',
     '당면과 부추를 조금 넣어 얇고 납작하게 빚어 구운 만두. 간장·고춧가루 양념을 뿌려 먹습니다.',
     '납작만두', 45, FALSE),
    ('ttaro-gukbap', '따로국밥', (SELECT id FROM dish WHERE normalized_name = '따로국밥'), '27', '대구',
     '소고기와 대파·무를 넣고 얼큰하게 끓인 국에 밥을 따로 냅니다. 대구 중구 일대에서 시작했습니다.',
     '따로국밥', 46, FALSE),
    ('yeongdeok-daege', '영덕대게', (SELECT id FROM dish WHERE normalized_name = '영덕대게'), '47', '경북 영덕',
     '영덕·울진 앞바다에서 잡은 대게를 쪄 냅니다. 게딱지에 밥을 비벼 먹기도 합니다.',
     '대게', 85, FALSE),
    ('gyeongju-ssambap', '경주 쌈밥', (SELECT id FROM dish WHERE normalized_name = '쌈밥'), '47', '경북 경주',
     '여러 가지 쌈채소에 불고기·강된장을 곁들여 싸 먹습니다. 경주 대릉원 옆 쌈밥 거리가 유명합니다.',
     '쌈밥', 86, FALSE),
    ('jinju-naengmyeon', '진주냉면', (SELECT id FROM dish WHERE normalized_name = '진주냉면'), '48', '경남 진주',
     '해물 육수에 메밀면을 말고 육전을 채 썰어 올립니다. 진주의 오래된 냉면입니다.',
     '진주냉면', 135, FALSE);

INSERT INTO local_food_tip (food_id, phrase, sort_order) VALUES
    ('gomjangeo', '양념은 따로 주세요', 1),
    ('gomjangeo', '덜 맵게 해 주세요', 2),
    ('napjak-mandu', '간장 양념은 따로 주세요', 1),
    ('ttaro-gukbap', '국물은 조금만 주세요', 1),
    ('ttaro-gukbap', '덜 맵게 해 주세요', 2),
    ('yeongdeok-daege', '초간장은 따로 주세요', 1),
    ('gyeongju-ssambap', '쌈장은 조금만 주세요', 1),
    ('gyeongju-ssambap', '강된장은 따로 주세요', 2),
    ('jinju-naengmyeon', '육수는 조금만 주세요', 1),
    ('jinju-naengmyeon', '육전 고명은 빼 주세요', 2);
