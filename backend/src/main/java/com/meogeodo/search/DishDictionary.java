package com.meogeodo.search;

import com.meogeodo.domain.Dish;
import com.meogeodo.domain.DishRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 음식 사전 — 정규화한 음식 이름에서 우리가 분석한 {@code dish} 를 찾는다.
 *
 * <p>관광공사 데이터(식당·메뉴 원문)는 저장하지 않지만, 음식 이름별 분석 결과는
 * 우리가 만든 자산이라 DB 에 둔다. 메뉴는 요청마다 관광공사에서 받아 오고, 그
 * 이름으로 이 사전을 찾아 판정한다.
 *
 * <p>사전에 없는 음식을 만나면 <b>이름만 등록</b>한다. {@code tagged_at} 이 비어 있어
 * 태깅 큐에 자동으로 들어가고, 분석되기 전까지 화면에는 "분석 전"으로 나간다.
 */
@Service
public class DishDictionary {

  private static final Logger log = LoggerFactory.getLogger(DishDictionary.class);

  /** {@code dish.normalized_name} 컬럼 길이. 넘으면 메뉴 이름이 아니라 설명문이다. */
  static final int MAX_NAME_LENGTH = 100;

  private final DishRepository dishes;
  private final TransactionTemplate newTx;

  public DishDictionary(DishRepository dishes, PlatformTransactionManager txManager) {
    this.dishes = dishes;
    this.newTx = new TransactionTemplate(txManager);
    // 검색·카드 트랜잭션과 묶지 않는다. 동시에 같은 음식을 등록하다 한쪽이 중복 키로
    // 실패해도 바깥 요청까지 롤백되면 안 된다.
    this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * 이름별 음식. 사전에 없던 이름은 새로 등록해서 돌려준다.
   *
   * @return 키는 정규화 이름. 너무 긴 이름은 빠진다
   */
  public Map<String, Dish> resolve(Collection<String> normalizedNames) {
    Set<String> wanted = new LinkedHashSet<>();
    for (String name : normalizedNames) {
      if (name != null && !name.isBlank() && name.length() <= MAX_NAME_LENGTH) {
        wanted.add(name);
      }
    }
    Map<String, Dish> out = new HashMap<>();
    if (wanted.isEmpty()) {
      return out;
    }
    dishes.findByNormalizedNameIn(wanted).forEach(d -> out.put(d.getNormalizedName(), d));

    for (String name : wanted) {
      if (!out.containsKey(name)) {
        Dish dish = register(name);
        if (dish != null) {
          out.put(name, dish);
        }
      }
    }
    return out;
  }

  private Dish register(String name) {
    try {
      Dish created = newTx.execute(status -> dishes.saveAndFlush(new Dish(name)));
      log.info("새 음식 등록 — 태깅 대기: {}", name);
      return created;
    } catch (DataIntegrityViolationException e) {
      // 다른 요청이 같은 이름을 먼저 등록했다.
      return dishes.findByNormalizedName(name).orElse(null);
    }
  }
}
