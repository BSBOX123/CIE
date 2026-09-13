package com.meogeodo.domain;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 판정에 필요한 메뉴·태그를 한 번에 읽는다. */
public interface MenuQueryRepository extends Repository<Menu, Long> {

  @Query(value = """
      SELECT m.restaurant_id AS restaurantId,
             m.id            AS menuId,
             m.raw_name      AS menuName,
             m.price         AS price,
             m.is_representative AS representative,
             d.id            AS dishId,
             CASE WHEN d.tagged_at IS NULL THEN 0 ELSE 1 END AS tagged,
             t.tag_type      AS tagType,
             t.tag_value     AS tagValue,
             t.amount        AS amount,
             t.source        AS source
      FROM menu m
      LEFT JOIN dish d     ON d.id = m.dish_id
      LEFT JOIN dish_tag t ON t.dish_id = d.id
      WHERE m.restaurant_id IN (:restaurantIds)
      ORDER BY m.restaurant_id, m.is_representative DESC, m.id
      """, nativeQuery = true)
  List<MenuTagView> findMenusWithTags(@Param("restaurantIds") List<Long> restaurantIds);
}
