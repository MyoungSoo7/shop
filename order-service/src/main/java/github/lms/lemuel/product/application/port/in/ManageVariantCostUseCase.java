package github.lms.lemuel.product.application.port.in;

import java.math.BigDecimal;
import java.util.List;

/**
 * SKU 매입가 조회·변경 (관리자 전용).
 *
 * <p>이 유스케이스가 내보내는 값은 <b>구매자 화면에 절대 실려서는 안 된다.</b> 원가와 마진은
 * 협상 카드이고, 한 번 새면 되돌릴 수 없다. 그래서 기존 SKU 응답({@code VariantResponse})에
 * 필드를 얹지 않고 별도 유스케이스·별도 경로로 분리했다 — 같은 응답 객체를 쓰면 구매자가 부르는
 * {@code POST /products/{id}/variants/resolve} 로도 함께 나간다.
 */
public interface ManageVariantCostUseCase {

    /**
     * 상품 한 건의 SKU 별 원가·마진.
     */
    List<VariantCostView> listByProductId(Long productId);

    /**
     * 매입가를 정하거나({@code purchasePrice} 값) 지운다({@code null}).
     *
     * @param productId 경로의 상품 — 이 SKU 가 정말 그 상품 것인지 확인하는 데 쓴다.
     */
    VariantCostView changePurchasePrice(Long productId, Long variantId, BigDecimal purchasePrice);

    /**
     * @param sellingPrice  지금 이 SKU 를 파는 값 (기준가 + 추가금 - 할인). 저장된 값이 아니라 계산된 값이다.
     * @param purchasePrice 사 오는 값. {@code null} 은 미입력이며 0원 매입이 아니다.
     * @param marginAmount  판매가 - 매입가. 매입가를 모르면 {@code null}. 음수(역마진)면 음수 그대로 둔다.
     * @param marginRate    마진액 / <b>판매가</b> × 100 (매출총이익률). 매입가 대비 가산율이 아니다.
     *                      매입가를 모르거나 판매가가 0 이면 {@code null}.
     */
    record VariantCostView(
            Long variantId,
            String sku,
            String optionName,
            int stockQuantity,
            BigDecimal sellingPrice,
            BigDecimal purchasePrice,
            BigDecimal marginAmount,
            BigDecimal marginRate) {}
}
