-- 관광공사 데이터를 DB 에서 지운다.
--
-- 공모전 규정: 공사 OpenAPI 데이터는 실시간으로 불러야 하며 DB 에 적재·캐싱할 수
-- 없다. V9 에서 우리 기록(제보·카드·속성)의 식당 id 를 contentid 로 옮겼고,
-- 운영에서 옮긴 결과(제보의 가게 이름이 실시간으로 나오는지)를 확인했다.
-- 이제 적재해 두었던 식당·메뉴 원문은 쓰는 곳이 없다.
--
-- 음식 사전(dish, dish_tag)은 우리가 분석한 결과라 남긴다.
-- restaurant_local_food 는 우리가 고른 식당-향토음식 연결이라 남긴다 (현재 0건).

DROP TABLE IF EXISTS menu_tag_override;
DROP TABLE IF EXISTS menu;
DROP TABLE IF EXISTS restaurant;
