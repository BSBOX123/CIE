package com.meogeodo.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RestaurantFlagRepository
    extends JpaRepository<RestaurantFlag, RestaurantFlag.Key> {

  List<RestaurantFlag> findByRestaurantIdIn(List<Long> restaurantIds);
}
