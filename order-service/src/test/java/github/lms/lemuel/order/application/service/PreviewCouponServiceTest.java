package github.lms.lemuel.order.application.service;

import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.coupon.application.port.out.LoadCouponPort;
import github.lms.lemuel.coupon.application.port.out.SaveCouponPort;
import github.lms.lemuel.coupon.application.service.CouponService;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.in.PreviewCouponUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 미리보기가 <b>결제와 같은 값</b>을 내놓는지에 대한 테스트.
 *
 * <p>여기서 진짜 {@link CouponService} 를 쓰는 건 의도적이다. 쿠폰 계산을 목으로 대체하면
 * "미리보기가 결제와 같은 규칙을 쓴다"는 바로 그 성질이 검증에서 빠진다.
 */
@ExtendWith(MockitoExtension.class)
class PreviewCouponServiceTest {

    @Mock LoadProductPort loadProductPort;
    @Mock LoadProductVariantPort loadVariantPort;
    @Mock LoadCouponPort loadCouponPort;
    @Mock SaveCouponPort saveCouponPort;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Clock clock =
            Clock.fixed(LocalDateTime.of(2026, 3, 1, 12, 0).atZone(KST).toInstant(), KST);

    private PreviewCouponUseCase service;

    @BeforeEach
    void setUp() {
        CouponUseCase couponUseCase = new CouponService(loadCouponPort, saveCouponPort, clock);
        service = new PreviewCouponService(loadProductPort, loadVariantPort, couponUseCase);
    }

    private void product(Long id, String price) {
        Product p = spy(Product.create("상품" + id, "설명", new BigDecimal(price), 100));
        when(p.getId()).thenReturn(id);
        when(loadProductPort.findById(id)).thenReturn(Optional.of(p));
    }

    private static List<CreateMultiItemOrderUseCase.Line> cart() {
        return List.of(
                new CreateMultiItemOrderUseCase.Line(100L, null, 1),
                new CreateMultiItemOrderUseCase.Line(999L, null, 1));
    }

    @Test
    @DisplayName("상품 전용 쿠폰의 미리보기 할인액은 결제에 적용될 값과 같다")
    void previewMatchesChargedDiscount() {
        product(100L, "10000");
        product(999L, "90000");
        Coupon coupon = Coupon.create("P10", CouponType.PERCENTAGE, new BigDecimal("10"),
                BigDecimal.ZERO, null, 100, LocalDateTime.of(2026, 12, 31, 0, 0));
        coupon.configureTarget("PRODUCT", 100L);
        coupon.assignId(1L);
        when(loadCouponPort.findByCode("P10")).thenReturn(Optional.of(coupon));

        var preview = service.preview(1L, "P10", cart());

        assertThat(preview.valid()).isTrue();
        assertThat(preview.subtotal()).isEqualByComparingTo("100000");
        // 소계의 10%(10,000)가 아니라 대상 라인 10,000 의 10% = 1,000.
        assertThat(preview.discountAmount()).isEqualByComparingTo("1000");
        assertThat(preview.eligibleAmount()).isEqualByComparingTo("10000");
        assertThat(preview.finalAmount()).isEqualByComparingTo("99000");
    }

    @Test
    @DisplayName("쿠폰 코드가 없으면 소계만 계산한다")
    void previewWithoutCoupon() {
        product(100L, "10000");
        product(999L, "90000");

        var preview = service.preview(1L, null, cart());

        assertThat(preview.valid()).isTrue();
        assertThat(preview.discountAmount()).isEqualByComparingTo("0");
        assertThat(preview.finalAmount()).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("쓸 수 없는 쿠폰은 예외가 아니라 valid=false 로 돌려준다 — 장바구니에서 여러 번 눌리는 경로다")
    void unusableCouponIsReportedNotThrown() {
        product(100L, "10000");
        product(999L, "90000");
        Coupon coupon = Coupon.create("P10", CouponType.PERCENTAGE, new BigDecimal("10"),
                BigDecimal.ZERO, null, 100, LocalDateTime.of(2026, 12, 31, 0, 0));
        coupon.configureTarget("PRODUCT", 555L);
        coupon.assignId(1L);
        when(loadCouponPort.findByCode("P10")).thenReturn(Optional.of(coupon));

        var preview = service.preview(1L, "P10", cart());

        assertThat(preview.valid()).isFalse();
        assertThat(preview.discountAmount()).isEqualByComparingTo("0");
        assertThat(preview.finalAmount()).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("없는 상품은 미리보기에서도 즉시 드러난다")
    void unknownProductFails() {
        when(loadProductPort.findById(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.preview(1L, "P10",
                List.of(new CreateMultiItemOrderUseCase.Line(100L, null, 1))))
                .isInstanceOf(ProductNotFoundException.class);
    }
}
