-- 식당 id 를 관광공사 contentid 로 바꾼다.
--
-- 공모전 규정: 공사 OpenAPI 데이터는 실시간으로 불러야 하며 DB 에 적재·캐싱해
-- 서빙할 수 없다. 그래서 restaurant / menu 테이블을 더 이상 쓰지 않고, 식당은
-- 요청마다 관광공사에서 받는다. 우리 쪽 기록(제보·카드·속성)이 가리키던
-- restaurant.id(적재 순번)를 contentid 로 옮겨야 다시 찾을 수 있다.
--
-- menu_id 는 옮길 곳이 없다. 메뉴는 이제 저장하지 않고, 메뉴 id 는 메뉴 이름에서
-- 만든 값이라 예전 순번과 대응하지 않는다. 카드에 찍힌 메뉴 이름(menu_label)은
-- 그대로 남으므로 기록이 사라지지는 않는다.
--
-- MySQL 은 컬럼 옆에 적은 REFERENCES 를 무시하므로(V1) 풀어야 할 외래키가 없다.
-- restaurant / menu 테이블 자체는 운영에서 옮긴 결과를 확인한 뒤 다음 마이그레이션에서 지운다.

UPDATE review r
  JOIN restaurant x ON x.id = r.restaurant_id
   SET r.restaurant_id = CAST(x.content_id AS UNSIGNED);

UPDATE order_card c
  JOIN restaurant x ON x.id = c.restaurant_id
   SET c.restaurant_id = CAST(x.content_id AS UNSIGNED),
       c.menu_id = NULL;

UPDATE order_card SET menu_id = NULL WHERE menu_id IS NOT NULL;

UPDATE restaurant_flag f
  JOIN restaurant x ON x.id = f.restaurant_id
   SET f.restaurant_id = CAST(x.content_id AS UNSIGNED);

UPDATE visit_log v
  JOIN restaurant x ON x.id = v.restaurant_id
   SET v.restaurant_id = CAST(x.content_id AS UNSIGNED);

UPDATE restaurant_local_food l
  JOIN restaurant x ON x.id = l.restaurant_id
   SET l.restaurant_id = CAST(x.content_id AS UNSIGNED);
