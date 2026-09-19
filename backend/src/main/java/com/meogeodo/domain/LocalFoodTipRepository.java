package com.meogeodo.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocalFoodTipRepository extends JpaRepository<LocalFoodTip, Long> {
  List<LocalFoodTip> findByFoodIdOrderBySortOrderAsc(String foodId);
}
