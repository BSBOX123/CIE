package com.meogeodo.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DishRepository extends JpaRepository<Dish, Long> {
  Optional<Dish> findByNormalizedName(String normalizedName);

  /** 검색 한 번에 나온 메뉴들을 한 번의 쿼리로 사전에서 찾는다. */
  List<Dish> findByNormalizedNameIn(Collection<String> normalizedNames);

  /** 아직 태깅되지 않은 음식. 태깅 배치의 작업 큐가 된다 (SPEC 7.4). */
  List<Dish> findTop200ByTaggedAtIsNull();
}
