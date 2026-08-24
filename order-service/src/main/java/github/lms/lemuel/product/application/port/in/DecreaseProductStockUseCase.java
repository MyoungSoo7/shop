package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.Product;

/**
 * 옵션 없는 일반 상품의 재고 차감 인바운드 포트.
 *
 * <p>옵션(SKU) 상품 재고는 {@link DecreaseVariantStockUseCase} 가, 옵션 없는 상품 재고는
 * 이 포트가 담당한다 — 두 경로 모두 원자적 조건부 UPDATE 기반이라 초과판매를 같은 기준으로 방어한다.
 */
public interface DecreaseProductStockUseCase {

    /**
     * 상품 재고를 {@code quantity} 만큼 원자적으로 차감하고 차감 후 상태를 반환한다.
     *
     * @throws IllegalArgumentException 수량이 0 이하이거나 상품이 존재하지 않을 때
     * @throws IllegalStateException    단종된 상품일 때
     * @throws github.lms.lemuel.product.domain.exception.InsufficientStockException 재고 부족일 때
     */
    Product decrease(Long productId, int quantity);
}
