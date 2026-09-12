package com.meogeodo.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DishRepository extends JpaRepository<Dish, Long> {
  Optional<Dish> findByNormalizedName(String normalizedName);

  /** 아직 태깅되지 않은 음식. 태깅 배치의 작업 큐가 된다 (SPEC 7.4). */
  java.util.List<Dish> findTop200ByTaggedAtIsNull();
}
