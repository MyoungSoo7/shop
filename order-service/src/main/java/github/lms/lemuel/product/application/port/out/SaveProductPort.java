package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.Product;

public interface SaveProductPort {
    Product save(Product product);

    /**
     * 옵션 없는 일반 상품의 재고({@code products.stock_quantity})를 원자적 조건부 UPDATE 로 차감한다.
     * 반드시 {@code @Transactional} 컨텍스트에서 호출. 옵션(SKU) 상품 재고는
     * {@link SaveProductVariantPort#decreaseStockIfAvailable} 가 담당한다.
     *
     * @return 1 = 차감 성공, 0 = 차감 불가(재고 부족·단종·미존재). 경합 실패는 발생하지 않는다.
     */
    int decreaseStockIfAvailable(Long productId, int quantity);

    /**
     * 옵션 없는 일반 상품 재고를 원자적 UPDATE 로 원복(증가)한다 — 환불/취소 시 차감분 되돌리기.
     * 반드시 {@code @Transactional} 컨텍스트에서 호출.
     *
     * @return 1 = 원복 성공, 0 = 원복 스킵(단종·미존재). 경합 실패는 발생하지 않는다.
     */
    int increaseStock(Long productId, int quantity);
}
