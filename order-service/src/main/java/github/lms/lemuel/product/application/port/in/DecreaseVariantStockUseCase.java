package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.ProductVariant;

public interface DecreaseVariantStockUseCase {

    /**
     * 옵션(SKU) 재고를 quantity 만큼 차감.
     *
     * <p>동시성 정책: 원자적 조건부 UPDATE 로 "재고 검증 + 차감" 을 한 번에 처리한다. 같은 SKU 에
     * 동시 차감이 몰려도 락 대기·충돌 재시도 없이 보유 수량만큼만 성공하며 초과판매를 방지한다.
     *
     * @throws github.lms.lemuel.product.domain.exception.InsufficientStockException 재고 부족
     * @throws IllegalStateException 단종(DISCONTINUED) SKU
     * @throws IllegalArgumentException variant 미존재 또는 수량 <= 0
     */
    ProductVariant decrease(Long variantId, int quantity);
}
