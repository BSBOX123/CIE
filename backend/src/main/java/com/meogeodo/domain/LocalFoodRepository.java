package com.meogeodo.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocalFoodRepository extends JpaRepository<LocalFood, String> {
  List<LocalFood> findAllByOrderBySortOrderAsc();

  List<LocalFood> findByRegionCodeOrderBySortOrderAsc(String regionCode);
}
