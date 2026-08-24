package github.lms.lemuel.product.application.port.in;

/**
 * 옵션(SKU) 재고 원복(증가) 인바운드 포트 — 환불/취소 시 차감분 되돌리기.
 *
 * <p>{@link DecreaseVariantStockUseCase} 의 역연산.
 */
public interface IncreaseVariantStockUseCase {

    /**
     * 옵션(SKU) 재고를 {@code quantity} 만큼 원자적으로 원복(증가)한다.
     *
     * <p>단종(DISCONTINUED)·미존재 SKU 는 원복하지 않고 조용히 스킵한다(환불 성공을 되돌리지 않기 위함).
     *
     * @return 실제 원복됐으면 {@code true}, 스킵됐으면 {@code false}
     * @throws IllegalArgumentException 수량이 0 이하일 때
     */
    boolean increase(Long variantId, int quantity);
}
