package com.meogeodo.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuRepository extends JpaRepository<Menu, Long> {
  List<Menu> findByRestaurantId(Long restaurantId);

  void deleteByRestaurantId(Long restaurantId);
}
