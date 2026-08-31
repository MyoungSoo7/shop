package github.lms.lemuel.order.application.service;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;
import github.lms.lemuel.coupon.domain.DiscountTargetLine;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import github.lms.lemuel.order.domain.exception.UserNotExistsException;
import github.lms.lemuel.product.application.port.in.DecreaseProductStockUseCase;
import github.lms.lemuel.product.application.port.in.DecreaseVariantStockUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CreateMultiItemOrderServiceTest {

    @Mock LoadUserForOrderPort loadUserPort;
    @Mock LoadProductPort loadProductPort;
    @Mock LoadProductVariantPort loadVariantPort;
    @Mock DecreaseVariantStockUseCase decreaseStockUseCase;
    @Mock DecreaseProductStockUseCase decreaseProductStockUseCase;
    @Mock SaveOrderPort saveOrderPort;
    @Mock SendOrderNotificationPort sendNotificationPort;
    @Mock github.lms.lemuel.order.application.port.out.PublishOrderEventPort publishOrderEventPort;
    @Mock CouponUseCase couponUseCase;
    @Mock github.lms.lemuel.product.application.port.in.DescribeVariantOptionsUseCase describeVariantOptionsUseCase;
    @Mock github.lms.lemuel.product.application.port.in.DescribeProductOptionsUseCase describeProductOptionsUseCase;
    @Mock github.lms.lemuel.shipping.application.port.in.AssessShippingFeeUseCase assessShippingFeeUseCase;
    @Mock github.lms.lemuel.order.application.port.out.CreateShipmentPort createShipmentPort;
    @InjectMocks CreateMultiItemOrderService service;

    @org.junit.jupiter.api.BeforeEach
    void noShippingFeeByDefault() {
        when(assessShippingFeeUseCase.assess(any()))
                .thenReturn(github.lms.lemuel.shipping.domain.ShippingFeeAssessment.none());
        // 기본은 자유입력 축이 없는 상품이다. 이 스텁이 빠지면 describe() 가 null 을 주고,
        // 주문 생성 전체가 옵션과 무관한 NPE 로 죽는다.
        when(describeProductOptionsUseCase.describe(any()))
                .thenReturn(new github.lms.lemuel.product.application.port.in
                        .DescribeProductOptionsUseCase.ProductOptions(1L, List.of(), List.of()));
    }

    /**
     * 검증 통과 결과 — 전체 적용({@code ALL}) 쿠폰을 실어 보낸다.
     *
     * <p>{@code coupon} 을 null 로 두면 안 된다: 서비스는 이 쿠폰으로 "할인을 짊어질 라인"을
     * 골라내므로(대상 밖 라인이 할인 몫을 지면 부분 취소 환불이 어긋난다), 결과의 쿠폰이
     * 계산에 실제로 쓰인다.
     */
    private static CouponUseCase.ValidateResult validResult(String discount, String finalAmount,
                                                            String eligible) {
        Coupon coupon = Coupon.create("ALL", CouponType.FIXED, new BigDecimal(discount),
                BigDecimal.ZERO, null, 100, null);
        return new CouponUseCase.ValidateResult(true, "ok", new BigDecimal(discount),
                new BigDecimal(finalAmount), new BigDecimal(eligible), coupon);
    }

    private Product mockProduct(Long id, String name, BigDecimal price) {
        Product p = Product.create(name, "설명", price, 100);
        // use reflection or mock
        Product spy = spy(p);
        when(spy.getId()).thenReturn(id);
        return spy;
    }

    @Test @DisplayName("create: 상품만 있는 주문 성공 (variant 없음)")
    void create_noVariant() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 2));
        Order result = service.create(1L, lines);
        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(1);
        // 옵션 없는 일반 상품도 재고 차감되어야 한다 (variant 경로는 미진입)
        verify(decreaseProductStockUseCase).decrease(10L, 2);
        verify(decreaseStockUseCase, never()).decrease(any(), anyInt());
        verify(sendNotificationPort).sendOrderConfirmation(eq("user@test.com"), any());
    }

    @Test @DisplayName("create: 산정된 배송비가 결제 금액에 더해지고 주문에 보존된다")
    void create_appliesShippingFee() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assessShippingFeeUseCase.assess(any())).thenReturn(
                new github.lms.lemuel.shipping.domain.ShippingFeeAssessment(
                        new BigDecimal("3000"), List.of()));

        Order result = service.create(1L, List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 2)));

        assertThat(result.getAmount()).isEqualByComparingTo("23000"); // 20000 + 3000
        assertThat(result.getShippingFee()).isEqualByComparingTo("3000");
    }

    @Test @DisplayName("create: 배송비 산정에는 쿠폰 할인 전 라인 금액이 전달된다")
    void create_shippingAssessedOnPreDiscountAmount() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(couponUseCase.validateCoupon(eq("SALE"), eq(1L), any()))
                .thenReturn(validResult("5000", "15000", "20000"));

        service.create(1L, List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 2)), "SALE");

        verify(assessShippingFeeUseCase).assess(argThat(lines ->
                lines.size() == 1
                        && lines.get(0).lineAmount().compareTo(new BigDecimal("20000")) == 0));
    }

    @Test @DisplayName("create: SKU 있는 주문 — 재고 차감")
    void create_withVariant() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));

        ProductVariant variant = ProductVariant.create(10L, "SKU-001", "빨강", new BigDecimal("1000"), 50);
        ProductVariant variantSpy = spy(variant);
        when(variantSpy.getProductId()).thenReturn(10L);
        when(loadVariantPort.loadById(20L)).thenReturn(Optional.of(variantSpy));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, 20L, 1));
        Order result = service.create(1L, lines);
        assertThat(result).isNotNull();
        verify(decreaseStockUseCase).decrease(20L, 1);
        // SKU 라인은 일반 상품 재고 차감 경로를 타지 않는다
        verify(decreaseProductStockUseCase, never()).decrease(any(), anyInt());
    }

    @Test @DisplayName("create: 사용자 없으면 예외")
    void create_userNotFound() {
        when(loadUserPort.findEmailById(99L)).thenReturn(Optional.empty());
        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        assertThatThrownBy(() -> service.create(99L, lines))
                .isInstanceOf(UserNotExistsException.class);
    }

    @Test @DisplayName("create: 상품 없으면 예외")
    void create_productNotFound() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.empty());
        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        assertThatThrownBy(() -> service.create(1L, lines))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test @DisplayName("create+쿠폰: 검증 성공 → 할인 반영 + 같은 트랜잭션에서 사용 기록")
    void create_withValidCoupon_appliesDiscountAndRecordsUsage() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.assignId(500L);
            return o;
        });
        // 소계 20,000 (10,000 x 2) 기준 2,000원 할인
        when(couponUseCase.validateCoupon(eq("SAVE2000"), eq(1L), any()))
                .thenReturn(validResult("2000", "18000", "20000"));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 2));
        Order result = service.create(1L, lines, "SAVE2000");

        // amount = 소계 20,000 - 할인 2,000 = 18,000
        assertThat(result.getAmount()).isEqualByComparingTo("18000");
        // 검증에는 소계 하나가 아니라 라인 목록이 넘어간다 — 쿠폰 대상이 여기서 판정되기 때문이다.
        verify(couponUseCase).validateCoupon("SAVE2000", 1L,
                List.of(new DiscountTargetLine(10L, null, new BigDecimal("20000"))));
        // 사용 기록은 저장된 orderId 로, 같은 흐름에서 호출
        verify(couponUseCase).useCoupon("SAVE2000", 1L, 500L);
    }

    @Test @DisplayName("create+쿠폰: 검증 실패 → 예외 + 주문 저장/쿠폰 사용 모두 미수행(롤백)")
    void create_withInvalidCoupon_throwsAndDoesNotSaveOrder() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(couponUseCase.validateCoupon(eq("EXPIRED"), eq(1L), any()))
                .thenReturn(new CouponUseCase.ValidateResult(
                        false, "만료된 쿠폰입니다.", BigDecimal.ZERO, new BigDecimal("10000"),
                        BigDecimal.ZERO, null));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        assertThatThrownBy(() -> service.create(1L, lines, "EXPIRED"))
                .isInstanceOf(CreateMultiItemOrderService.CouponApplicationException.class)
                .hasMessageContaining("만료된 쿠폰");

        verify(saveOrderPort, never()).save(any());
        verify(couponUseCase, never()).useCoupon(any(), any(), any());
        verify(publishOrderEventPort, never()).publishOrderCreated(any(), any(), any(), any(), any(), any());
    }

    @Test @DisplayName("create+쿠폰: 사용 기록 실패(한도/중복) → 예외 전파로 트랜잭션 롤백")
    void create_couponUseFails_propagatesForRollback() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(couponUseCase.validateCoupon(eq("LIMIT"), eq(1L), any()))
                .thenReturn(validResult("1000", "9000", "10000"));
        doThrow(new IllegalStateException("쿠폰 사용 한도를 초과했습니다."))
                .when(couponUseCase).useCoupon(eq("LIMIT"), eq(1L), any());

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        assertThatThrownBy(() -> service.create(1L, lines, "LIMIT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("한도");
        // 사용 기록 실패는 useCoupon 이후 — 이벤트 발행까지 도달하지 않아야 롤백 의미가 산다
        verify(publishOrderEventPort, never()).publishOrderCreated(any(), any(), any(), any(), any(), any());
    }

    @Test @DisplayName("create: 쿠폰 코드 없으면(null) 쿠폰 경로 미진입")
    void create_noCoupon_skipsCouponFlow() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        service.create(1L, lines, null);

        verifyNoInteractions(couponUseCase);
    }

    @Test @DisplayName("create: variant가 product에 속하지 않으면 예외")
    void create_variantMismatch() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));

        ProductVariant variant = ProductVariant.create(99L, "SKU", "opt", BigDecimal.ZERO, 10);
        ProductVariant variantSpy = spy(variant);
        when(variantSpy.getProductId()).thenReturn(99L); // different product
        when(loadVariantPort.loadById(20L)).thenReturn(Optional.of(variantSpy));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, 20L, 1));
        assertThatThrownBy(() -> service.create(1L, lines))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("variant 가 product 에 속하지 않음");
    }

    private static github.lms.lemuel.order.domain.ShippingAddressSnapshot address() {
        return new github.lms.lemuel.order.domain.ShippingAddressSnapshot(
                "홍길동", "010-1234-5678", "06236", "서울시 강남구 테헤란로 1", "3층", "부재시 경비실");
    }

    @Test @DisplayName("create: 배송지는 저장 전에 주문에 굳고, 같은 트랜잭션에서 배송이 생성된다")
    void create_attachesAddressBeforeSaveAndCreatesShipment() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        // save 시점에 이미 배송지가 붙어 있어야 INSERT 와 같은 행에 들어간다
        when(saveOrderPort.save(any())).thenAnswer(inv -> {
            Order given = inv.getArgument(0);
            assertThat(given.getShippingAddress()).isEqualTo(address());
            return given;
        });

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        Order result = service.create(1L, lines, null, address());

        assertThat(result.getShippingAddress()).isEqualTo(address());
        verify(createShipmentPort).createForOrder(any(), eq(address()));
    }

    @Test @DisplayName("create: 배송지가 없으면(레거시 호출) 배송을 만들지 않는다")
    void create_withoutAddress_doesNotCreateShipment() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order result = service.create(1L, List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1)));

        assertThat(result.getShippingAddress()).isNull();
        verifyNoInteractions(createShipmentPort);
    }

    /* ─────────────────────────────────────────
       자유입력(TEXT) 축 — 각인 문구처럼 구매자가 직접 적는 옵션.
       SKU 를 만들지 않으므로 재고·조합에는 안 끼고 주문 라인에만 남는다.
       ───────────────────────────────────────── */

    /** 자유입력 축 하나를 가진 상품의 옵션 트리. required 는 호출자가 정한다. */
    private void givenTextAxis(String code, String name, int maxLength, boolean required) {
        var axis = new github.lms.lemuel.product.application.port.in
                .DescribeProductOptionsUseCase.Axis(
                        0, code, name, github.lms.lemuel.product.domain.OptionInputType.TEXT,
                        required, List.of(), maxLength);
        when(describeProductOptionsUseCase.describe(any()))
                .thenReturn(new github.lms.lemuel.product.application.port.in
                        .DescribeProductOptionsUseCase.ProductOptions(10L, List.of(axis), List.of()));
    }

    private void givenSellableProduct() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        // mockProduct 는 안에서 다시 stub 한다 — thenReturn 인자 자리에서 부르면
        // Mockito 가 스터빙 중첩으로 보고 UnfinishedStubbing 을 던진다. 먼저 만들어 둔다.
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test @DisplayName("create: 적어 보낸 문구가 라인 옵션 스냅샷으로 남는다")
    void create_snapshotsFreeText() {
        givenSellableProduct();
        givenTextAxis("ENGRAVING", "각인 문구", 10, false);

        Order result = service.create(1L, List.of(new CreateMultiItemOrderUseCase.Line(
                10L, null, 1, java.util.Map.of("ENGRAVING", "민수에게"))));

        var options = result.getItems().get(0).getOptions();
        assertThat(options).hasSize(1);
        assertThat(options.get(0).isFreeText()).isTrue();
        assertThat(options.get(0).getTextValue()).isEqualTo("민수에게");
        // 문구는 재고 단위를 가르지 않는다 — SKU 차감 경로로 새지 않아야 한다.
        verify(decreaseStockUseCase, never()).decrease(any(), anyInt());
    }

    /*
     * 화면이 maxlength 를 걸어도 요청을 직접 만들면 그 속성은 없는 것이다.
     * 그래서 축이 정한 상한을 주문 시점에 다시 센다.
     */
    @Test @DisplayName("create: 축 상한을 넘는 문구는 주문을 거절한다")
    void create_rejectsOverlongText() {
        givenSellableProduct();
        givenTextAxis("ENGRAVING", "각인 문구", 5, false);

        assertThatThrownBy(() -> service.create(1L, List.of(new CreateMultiItemOrderUseCase.Line(
                10L, null, 1, java.util.Map.of("ENGRAVING", "일이삼사오육")))))
                .isInstanceOf(OrderInvariantViolationException.class);
        verify(saveOrderPort, never()).save(any());
    }

    /*
     * 조용히 버리면 구매자는 각인을 적었다고 믿고 결제하는데 주문서엔 아무것도 없다.
     * 그 차이는 물건이 도착해서야 드러난다.
     */
    @Test @DisplayName("create: 상품에 없는 자유입력 축 코드는 거절한다 — 조용히 버리지 않는다")
    void create_rejectsUnknownTextAxis() {
        givenSellableProduct();
        givenTextAxis("ENGRAVING", "각인 문구", 10, false);

        assertThatThrownBy(() -> service.create(1L, List.of(new CreateMultiItemOrderUseCase.Line(
                10L, null, 1, java.util.Map.of("GIFT_NOTE", "축하해")))))
                .isInstanceOf(OrderInvariantViolationException.class);
        verify(saveOrderPort, never()).save(any());
    }

    /*
     * 필수 여부는 상품이 정한 것이다. 화면이 칸을 안 그려줬다는 이유로 통과시키면
     * required 의 뜻이 사라진다 — 그래서 키를 아예 안 보낸 경우까지 여기서 막는다.
     */
    @Test @DisplayName("create: 필수 자유입력을 빼먹으면 거절한다")
    void create_rejectsMissingRequiredText() {
        givenSellableProduct();
        givenTextAxis("ENGRAVING", "각인 문구", 10, true);

        assertThatThrownBy(() -> service.create(1L,
                List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1))))
                .isInstanceOf(OrderInvariantViolationException.class);
        verify(saveOrderPort, never()).save(any());
    }

    @Test @DisplayName("create: 선택 자유입력을 비워 두면 스냅샷 없이 통과한다")
    void create_allowsBlankOptionalText() {
        givenSellableProduct();
        givenTextAxis("ENGRAVING", "각인 문구", 10, false);

        Order result = service.create(1L,
                List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1)));

        assertThat(result.getItems().get(0).getOptions()).isEmpty();
    }
}
