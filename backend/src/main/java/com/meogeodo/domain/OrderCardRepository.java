package com.meogeodo.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderCardRepository extends JpaRepository<OrderCard, Long> {
  Optional<OrderCard> findByIdAndUserId(Long id, Long userId);

  List<OrderCard> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);
}
