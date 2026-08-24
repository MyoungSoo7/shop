package github.lms.lemuel.product.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 상품의 <b>대표 분류</b> 조회 포트.
 *
 * <p>대표 분류의 정본은 카테고리 컨텍스트가 소유하는 {@code product_ecommerce_categories.is_primary}
 * 다(폐기된 {@code products.category_id} 의 후임). product 어댑터가 그 테이블을 직접 읽으면 어댑터가
 * 타 도메인의 영속 계층에 묶이므로, 포트를 두고 카테고리 쪽 어댑터가 구현한다.
 */
public interface LoadPrimaryCategoryPort {

    Optional<Long> findPrimaryCategoryId(Long productId);

    /** 목록 경로용 일괄 조회 — 상품마다 한 번씩 묻지 않는다(N+1 방지). 대표가 없는 상품은 키가 빠진다. */
    Map<Long, Long> findPrimaryCategoryIds(Collection<Long> productIds);
}
