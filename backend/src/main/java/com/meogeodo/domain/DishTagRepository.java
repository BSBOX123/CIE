package com.meogeodo.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DishTagRepository extends JpaRepository<DishTag, Long> {
  List<DishTag> findByDishId(Long dishId);

  List<DishTag> findByDishIdIn(List<Long> dishIds);

  void deleteByDishId(Long dishId);
}
