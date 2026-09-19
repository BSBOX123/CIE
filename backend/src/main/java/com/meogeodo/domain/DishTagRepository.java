package com.meogeodo.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DishTagRepository extends JpaRepository<DishTag, Long> {
  List<DishTag> findByDishId(Long dishId);

  List<DishTag> findByDishIdIn(List<Long> dishIds);

  /**
   * 음식까지 함께 읽는다. 검색은 관광공사 호출 동안 DB 연결을 잡아 두지 않으려고
   * 트랜잭션 밖에서 돌기 때문에, 지연 로딩에 기대면 안 된다.
   */
  @Query("SELECT t FROM DishTag t JOIN FETCH t.dish d WHERE d.id IN :dishIds")
  List<DishTag> findWithDishByDishIdIn(@Param("dishIds") Collection<Long> dishIds);

  void deleteByDishId(Long dishId);
}
