package github.lms.lemuel.order.application.service;

import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.coupon.domain.DiscountTargetLine;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.in.PreviewCouponUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 쿠폰 미리보기 — 주문 생성의 <b>가격 계산 부분만</b> 그대로 떼어 읽기 전용으로 돌린다.
 *
 * <p>단가 해석(SKU 옵션 추가금·할인 포함)과 카테고리 해석을 {@code CreateMultiItemOrderService} 와
 * 똑같이 상품 마스터에서 하고, 할인 계산은 같은 {@code CouponUseCase.validateCoupon} 에 맡긴다.
 * 여기서 규칙을 다시 구현하면 두 경로가 언젠가 갈라지고, 갈라지는 순간 화면과 청구가 어긋난다.
 *
 * <p>재고는 건드리지 않는다 — 미리보기는 장바구니에서 여러 번 눌리는 경로다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PreviewCouponService implements PreviewCouponUseCase {

    private final LoadProductPort loadProductPort;
    private final LoadProductVariantPort loadVariantPort;
    private final CouponUseCase couponUseCase;

    @Override
    public Preview preview(Long userId, String couponCode, List<CreateMultiItemOrderUseCase.Line> lines) {
        List<DiscountTargetLine> targetLines = new ArrayList<>(lines.size());
        for (CreateMultiItemOrderUseCase.Line line : lines) {
            Product product = loadProductPort.findById(line.productId())
                    .orElseThrow(() -> new ProductNotFoundException(line.productId()));

            BigDecimal unitPrice = product.getPrice();
            if (line.variantId() != null) {
                ProductVariant variant = loadVariantPort.loadById(line.variantId())
                        .orElseThrow(() -> new ProductInvariantViolationException(
                                "ProductVariant not found: " + line.variantId()));
                if (!variant.getProductId().equals(product.getId())) {
                    throw new ProductInvariantViolationException(
                            "variant 가 product 에 속하지 않음: variant=" + line.variantId()
                                    + ", product=" + line.productId());
                }
                // 주문과 같은 우선순위로 옵션 단가를 반영한다 — 여기서만 기준가를 쓰면
                // 미리보기가 옵션 추가금을 빠뜨린 소계를 보여준다.
                unitPrice = variant.effectiveUnitPrice(product.getPrice());
            }

            targetLines.add(new DiscountTargetLine(
                    product.getId(),
                    product.getCategoryId(),
                    unitPrice.multiply(BigDecimal.valueOf(line.quantity()))));
        }

        BigDecimal subtotal = targetLines.stream()
                .map(DiscountTargetLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (couponCode == null || couponCode.isBlank()) {
            return new Preview(true, "쿠폰 없음", subtotal, BigDecimal.ZERO, BigDecimal.ZERO, subtotal);
        }

        CouponUseCase.ValidateResult result = couponUseCase.validateCoupon(couponCode, userId, targetLines);
        return new Preview(result.valid(), result.message(), subtotal,
                result.discountAmount(), result.eligibleAmount(), result.finalAmount());
    }
}
