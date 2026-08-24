package github.lms.lemuel.order.application.service;

import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.PublishOrderEventPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.OrderItemOption;
import github.lms.lemuel.order.domain.exception.UserNotExistsException;
import github.lms.lemuel.product.application.port.in.DecreaseProductStockUseCase;
import github.lms.lemuel.product.application.port.in.DescribeVariantOptionsUseCase;
import github.lms.lemuel.product.application.port.in.DecreaseVariantStockUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import github.lms.lemuel.shipping.application.port.in.AssessShippingFeeUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 다건 주문 생성 서비스.
 *
 * <p>흐름:
 * <ol>
 *   <li>사용자 존재 검증</li>
 *   <li>각 라인에 대해 상품/SKU 조회 → 단가·이름 스냅샷 추출</li>
 *   <li>SKU 가 지정된 라인은 재고 차감 (Optimistic Lock 자동 재시도)</li>
 *   <li>OrderItem 들 생성 + Order.createMultiItem 으로 합계 자동 계산</li>
 *   <li>저장 → 알림 발송</li>
 * </ol>
 *
 * <p>재고 차감과 Order 저장이 같은 트랜잭션이므로 PG 결제 실패 시 롤백되어
 * "재고는 빠졌는데 주문 안 만들어진" 정합성 깨짐을 방지한다.
 *
 * <p><b>쿠폰 결합:</b> {@code couponCode} 가 주어지면 소계(subtotal) 기준 쿠폰 검증 → 할인 반영 →
 * 주문 저장 → 쿠폰 사용 기록을 <b>모두 이 메서드의 단일 {@code @Transactional} 안에서</b> 수행한다.
 * 따라서 쿠폰 검증 실패(만료·최소금액 미달 등)나 사용 기록 실패(한도 초과·1인 1매 중복)가 발생하면
 * 주문 INSERT·재고 차감·Outbox 발행까지 전부 롤백되어, "쿠폰은 못 썼는데 주문만 생성된" 불일치를 차단한다.
 */
@Service
@Transactional
public class CreateMultiItemOrderService implements CreateMultiItemOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateMultiItemOrderService.class);

    private final LoadUserForOrderPort loadUserPort;
    private final LoadProductPort loadProductPort;
    private final LoadProductVariantPort loadVariantPort;
    private final DecreaseVariantStockUseCase decreaseStockUseCase;
    private final DecreaseProductStockUseCase decreaseProductStockUseCase;
    private final SaveOrderPort saveOrderPort;
    private final SendOrderNotificationPort sendNotificationPort;
    private final PublishOrderEventPort publishOrderEventPort;
    private final CouponUseCase couponUseCase;
    private final DescribeVariantOptionsUseCase describeVariantOptionsUseCase;
    private final AssessShippingFeeUseCase assessShippingFeeUseCase;

    public CreateMultiItemOrderService(LoadUserForOrderPort loadUserPort,
                                       LoadProductPort loadProductPort,
                                       LoadProductVariantPort loadVariantPort,
                                       DecreaseVariantStockUseCase decreaseStockUseCase,
                                       DecreaseProductStockUseCase decreaseProductStockUseCase,
                                       SaveOrderPort saveOrderPort,
                                       SendOrderNotificationPort sendNotificationPort,
                                       PublishOrderEventPort publishOrderEventPort,
                                       CouponUseCase couponUseCase,
                                       DescribeVariantOptionsUseCase describeVariantOptionsUseCase,
                                       AssessShippingFeeUseCase assessShippingFeeUseCase) {
        this.loadUserPort = loadUserPort;
        this.loadProductPort = loadProductPort;
        this.loadVariantPort = loadVariantPort;
        this.decreaseStockUseCase = decreaseStockUseCase;
        this.decreaseProductStockUseCase = decreaseProductStockUseCase;
        this.saveOrderPort = saveOrderPort;
        this.sendNotificationPort = sendNotificationPort;
        this.publishOrderEventPort = publishOrderEventPort;
        this.couponUseCase = couponUseCase;
        this.describeVariantOptionsUseCase = describeVariantOptionsUseCase;
        this.assessShippingFeeUseCase = assessShippingFeeUseCase;
    }

    @Override
    public Order create(Long userId, List<Line> lines, String couponCode) {
        log.info("다건 주문 생성: userId={}, lines={}, coupon={}", userId, lines.size(), couponCode);

        String userEmail = loadUserPort.findEmailById(userId)
                .orElseThrow(() -> new UserNotExistsException(userId));

        List<OrderItem> items = new ArrayList<>(lines.size());
        for (Line line : lines) {
            Product product = loadProductPort.findById(line.productId())
                    .orElseThrow(() -> new ProductNotFoundException(line.productId()));

            BigDecimal unitPrice = product.getPrice();
            String productName = product.getName();
            String sku = null;
            List<OrderItemOption> options = List.of();

            // SKU 라인이면 variant 조회 + 재고 차감 + 옵션 단가(추가금·할인) 반영
            if (line.variantId() != null) {
                ProductVariant variant = loadVariantPort.loadById(line.variantId())
                        .orElseThrow(() -> new ProductInvariantViolationException(
                                "ProductVariant not found: " + line.variantId()));
                if (!variant.getProductId().equals(product.getId())) {
                    throw new ProductInvariantViolationException(
                            "variant 가 product 에 속하지 않음: variant=" + line.variantId()
                                    + ", product=" + line.productId());
                }
                sku = variant.getSku();
                // 옵션 단가 = 기준가 + 추가금 - 정액할인 - 정률할인 (ProductVariant 가 우선순위 강제)
                unitPrice = variant.effectiveUnitPrice(product.getPrice());
                // 주문 시점 옵션 스냅샷 — 값이 비활성화되거나 개명돼도 이 주문서는 그대로 읽힌다.
                options = describeVariantOptionsUseCase.describe(line.variantId()).stream()
                        .map(d -> OrderItemOption.snapshot(d.sortOrder(), d.axisCode(), d.axisName(),
                                d.valueCode(), d.valueName()))
                        .toList();
                decreaseStockUseCase.decrease(line.variantId(), line.quantity());
            } else {
                // 옵션 없는 일반 상품: products.stock_quantity 를 원자적 조건부 UPDATE 로 차감.
                // 같은 트랜잭션이므로 이후 단계(쿠폰·결제) 실패 시 재고도 함께 롤백된다.
                decreaseProductStockUseCase.decrease(line.productId(), line.quantity());
            }

            items.add(OrderItem.newItem(line.productId(), line.variantId(), sku,
                    productName, unitPrice, line.quantity(), options));
        }

        // 소계 기준 쿠폰 검증 → 할인 금액 산출 (검증 실패 시 예외 → 트랜잭션 롤백)
        BigDecimal subtotal = items.stream()
                .map(OrderItem::getLineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean hasCoupon = couponCode != null && !couponCode.isBlank();
        BigDecimal discount = BigDecimal.ZERO;
        if (hasCoupon) {
            CouponUseCase.ValidateResult result =
                    couponUseCase.validateCoupon(couponCode, userId, subtotal);
            if (!result.valid()) {
                throw new CouponApplicationException(result.message());
            }
            discount = result.discountAmount();
        }

        // 배송비 산정 — 셀러별 조건부 무료배송 판정은 쿠폰 할인 전 라인 금액 기준이다
        // (쿠폰이 배송비를 좌우하면 "쿠폰을 썼더니 배송비가 생겼다"는 역진성이 생긴다).
        // 상품의 셀러·부과 유형은 요청이 아니라 상품 마스터에서 해석된다 — 클라이언트가 배송비를 정할 수 없다.
        BigDecimal shippingFee = assessShippingFeeUseCase.assess(
                items.stream()
                        .map(item -> new AssessShippingFeeUseCase.OrderLine(
                                item.getProductId(), item.getLineAmount()))
                        .toList()
        ).totalFee();

        Order order = Order.createMultiItem(userId, items, discount, shippingFee);
        Order saved = saveOrderPort.save(order);

        // 쿠폰 사용 기록 — 같은 트랜잭션. 한도 초과/1인 1매 중복이면 예외 → 주문·재고 차감까지 전부 롤백
        if (hasCoupon) {
            couponUseCase.useCoupon(couponCode, userId, saved.getId());
        }

        // ADR 0020 Phase 3b — settlement order 프로젝션 동기화용 OrderCreated 발행(같은 트랜잭션 Outbox)
        publishOrderEventPort.publishOrderCreated(
                saved.getId(), saved.getUserId(), saved.getProductId(),
                saved.getStatus().name(), saved.getAmount(), saved.getCreatedAt());

        log.info("다건 주문 생성 완료: orderId={}, items={}, subtotal={}, discount={}, amount={}",
                saved.getId(), saved.getItems().size(), subtotal, discount, saved.getAmount());

        sendNotificationPort.sendOrderConfirmation(userEmail, saved);
        return saved;
    }

    /**
     * 쿠폰 적용 실패(검증 단계). {@link IllegalArgumentException} 상속이라
     * 공통 {@code GlobalExceptionHandler} 가 400(Bad Request)으로 매핑하며, 트랜잭션은 롤백된다.
     */
    public static class CouponApplicationException extends IllegalArgumentException {
        public CouponApplicationException(String message) {
            super("쿠폰 적용 실패: " + message);
        }
    }
}
