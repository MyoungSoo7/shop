package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.CancelOrderItemsUseCase;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.OrderCouponRestorePort;
import github.lms.lemuel.order.application.port.out.RefundOrderPaymentPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import github.lms.lemuel.product.application.port.in.IncreaseProductStockUseCase;
import github.lms.lemuel.product.application.port.in.IncreaseVariantStockUseCase;
import github.lms.lemuel.shipping.application.port.in.AssessShippingFeeUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 라인 단위 부분 취소 서비스 — 취소 · 재고 복원 · 배송비 재산정 · 부분 환불을 한 트랜잭션으로 묶는다.
 *
 * <p><b>배송비 재부과(핵심 규칙)</b> — 무료배송 임계를 채우던 상품이 취소로 빠지면 면제됐던
 * 배송비가 되살아나고, 그 차액은 환불액에서 차감된다. 이 규칙이 없으면 "5 만원어치 주문 →
 * 무료배송 → 4 만원어치 취소 → 1 만원짜리를 배송비 없이 받는" 구멍이 생긴다. 레거시 커머스가
 * 부분취소 SQL 에서 남은 주문의 배송비를 다시 계산해 {@code SHIPPING_CHARGED_YN='Y'} 로 되돌리던
 * 처리를 도메인 계산기 재호출로 옮긴 것이다.
 *
 * <p><b>환불 공식(단일):</b> {@code refund = 취소라인합 + 기존배송비 - 새배송비} (0 하한).
 * 전량 취소면 새 배송비가 0 이라 이미 낸 배송비까지 자연스럽게 환불되고, 부분 취소에서 배송비가
 * 유지되면 취소 금액 그대로, 되살아나면 그만큼 덜 돌려준다 — 세 경우가 한 식으로 설명된다.
 *
 * <p><b>트랜잭션:</b> 라인 취소 · 재고 복원 · 배송비 갱신은 환불 호출 전에 저장되지만, PG 환불이
 * 실패하면 예외가 전파되어 전부 롤백된다("환불에 성공한 경우에만 확정"). 결제 전 주문은 돌려줄 돈이
 * 없으므로 환불을 호출하지 않는다.
 */
@Service
public class CancelOrderItemsService implements CancelOrderItemsUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelOrderItemsService.class);

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final SaveOrderStatusHistoryPort historyPort;
    private final RefundOrderPaymentPort refundOrderPaymentPort;
    private final IncreaseProductStockUseCase increaseProductStockUseCase;
    private final IncreaseVariantStockUseCase increaseVariantStockUseCase;
    private final AssessShippingFeeUseCase assessShippingFeeUseCase;
    private final OrderCouponRestorePort orderCouponRestorePort;

    public CancelOrderItemsService(LoadOrderPort loadOrderPort,
                                   SaveOrderPort saveOrderPort,
                                   SaveOrderStatusHistoryPort historyPort,
                                   RefundOrderPaymentPort refundOrderPaymentPort,
                                   IncreaseProductStockUseCase increaseProductStockUseCase,
                                   IncreaseVariantStockUseCase increaseVariantStockUseCase,
                                   AssessShippingFeeUseCase assessShippingFeeUseCase,
                                   OrderCouponRestorePort orderCouponRestorePort) {
        this.loadOrderPort = loadOrderPort;
        this.saveOrderPort = saveOrderPort;
        this.historyPort = historyPort;
        this.refundOrderPaymentPort = refundOrderPaymentPort;
        this.increaseProductStockUseCase = increaseProductStockUseCase;
        this.increaseVariantStockUseCase = increaseVariantStockUseCase;
        this.assessShippingFeeUseCase = assessShippingFeeUseCase;
        this.orderCouponRestorePort = orderCouponRestorePort;
    }

    @Override
    @Transactional
    public Result cancelItems(Long orderId, List<Long> itemIds, String reason, String operator) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus statusBefore = order.getStatus();
        List<OrderItem> targets = order.getItems().stream()
                .filter(item -> itemIds != null && itemIds.contains(item.getId()))
                .toList();

        // 1) 라인 취소 — 소속/중복 검증과 상태 가드는 도메인이 강제한다.
        BigDecimal canceledSubtotal = order.cancelItems(itemIds);

        // 2) 취소된 라인만 재고 복원. 전량 취소 경로의 claimStockRestorationOnCancel 과 달리
        //    여기서는 라인의 canceled_at 자체가 이중 복원을 막는 멱등 장치다.
        for (OrderItem item : targets) {
            if (item.getVariantId() != null) {
                increaseVariantStockUseCase.increase(item.getVariantId(), item.getQuantity());
            } else {
                increaseProductStockUseCase.increase(item.getProductId(), item.getQuantity());
            }
        }

        // 3) 남은 라인으로 배송비 재산정 — 무료배송 조건이 깨졌는지 여기서 드러난다.
        BigDecimal previousShippingFee = order.getShippingFee();
        BigDecimal newShippingFee = assessShippingFeeUseCase.assess(
                order.activeItems().stream()
                        .map(item -> new AssessShippingFeeUseCase.OrderLine(
                                item.getProductId(), item.getLineAmount()))
                        .toList()
        ).totalFee();
        BigDecimal additionalShippingFee = newShippingFee.subtract(previousShippingFee).max(BigDecimal.ZERO);
        order.assignShippingFee(newShippingFee);

        // 4) 환불액 = 취소라인합 + 기존배송비 - 새배송비 (0 하한 — 재부과가 취소액을 넘어도 추가 청구는 하지 않는다)
        boolean fullyCanceled = order.allItemsCanceled();
        boolean paid = statusBefore != OrderStatus.CREATED;
        // 결제 전 주문은 돌려줄 돈이 없다 — 환불 "예정액"이 아니라 실제 환불액을 보고한다.
        BigDecimal refundAmount = paid
                ? canceledSubtotal.add(previousShippingFee).subtract(newShippingFee).max(BigDecimal.ZERO)
                : BigDecimal.ZERO;

        if (fullyCanceled && !paid) {
            order.cancel();   // 결제 전 전량 취소는 그대로 주문 종결
        }
        if (fullyCanceled) {
            // 쿠폰은 <b>전량 취소일 때만</b> 되돌린다. 부분 취소에서는 남은 라인이 여전히 그 할인을
            // 받고 있으므로 돌려주면 같은 쿠폰을 두 번 쓰는 셈이 된다(할인 안분 재계산은 별개 주제).
            orderCouponRestorePort.restoreOnCanceled(orderId, "주문 라인 전량 취소");
        }
        saveOrderPort.save(order);
        historyPort.save(orderId, statusBefore.name(), order.getStatus().name(), operator,
                describe(reason, itemIds));

        // 5) 환불 — 결제가 있고 돌려줄 금액이 있을 때만. 멱등 키는 (주문, 취소 라인) 으로 안정적이라
        //    같은 요청 재시도가 PG 이중 환불로 이어지지 않는다.
        if (paid && refundAmount.signum() > 0) {
            refundOrderPaymentPort.refundOrderPayment(orderId, refundAmount, idempotencyKey(orderId, itemIds));
        }

        log.info("주문 부분 취소: orderId={}, items={}, canceled={}, shipping {}→{}, refund={}",
                orderId, itemIds, canceledSubtotal, previousShippingFee, newShippingFee, refundAmount);

        return new Result(orderId, canceledSubtotal, additionalShippingFee, refundAmount, fullyCanceled);
    }

    private String describe(String reason, List<Long> itemIds) {
        return "부분취소 items=" + itemIds + (reason == null || reason.isBlank() ? "" : " reason=" + reason);
    }

    /** 정렬된 라인 id 로 키를 만들어 요청 순서가 달라도 같은 취소는 같은 키가 되게 한다. */
    private String idempotencyKey(Long orderId, List<Long> itemIds) {
        List<Long> sorted = new ArrayList<>(itemIds);
        sorted.sort(Long::compareTo);
        return "order-" + orderId + "-items-"
                + sorted.stream().map(String::valueOf).collect(Collectors.joining("-"));
    }
}
