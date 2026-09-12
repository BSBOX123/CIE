package com.meogeodo.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {

  List<Review> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId);

  List<Review> findByUserIdOrderByCreatedAtDesc(Long userId);

  Optional<Review> findByIdAndUserId(Long id, Long userId);

  long countByRestaurantId(Long restaurantId);
}
