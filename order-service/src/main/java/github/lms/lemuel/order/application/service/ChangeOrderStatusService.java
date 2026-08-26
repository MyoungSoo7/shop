package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.OrderCouponRestorePort;
import github.lms.lemuel.order.application.port.out.OrderPointRewardPort;
import github.lms.lemuel.order.application.port.out.RefundOrderPaymentPort;
import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.OrderNotifiableEvent;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.RefundPolicy;
import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import github.lms.lemuel.product.application.port.in.IncreaseProductStockUseCase;
import github.lms.lemuel.product.application.port.in.IncreaseVariantStockUseCase;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 주문 상태 변경 서비스
 */
@Service
@RequiredArgsConstructor
public class ChangeOrderStatusService implements ChangeOrderStatusUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChangeOrderStatusService.class);

    /** 트랜잭션 스코프 통지 선점 장부의 키 — {@link #claimNotification} 참고. */
    private static final Object NOTIFY_CLAIM_KEY = new Object();

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final SaveOrderStatusHistoryPort historyPort;
    private final RefundOrderPaymentPort refundOrderPaymentPort;
    private final IncreaseProductStockUseCase increaseProductStockUseCase;
    private final IncreaseVariantStockUseCase increaseVariantStockUseCase;
    private final OrderPointRewardPort orderPointRewardPort;
    private final OrderCouponRestorePort orderCouponRestorePort;
    private final LoadUserForOrderPort loadUserForOrderPort;
    private final SendOrderNotificationPort sendOrderNotificationPort;

    @Override
    @Transactional
    public Order cancelOrder(Long orderId) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        // 이력은 이 경로만 예전부터 시작 상태를 CREATED 로 적어 왔다(실제 전이 전 상태와 다를 수 있다).
        // 통지에는 진짜 이전 상태를 쓴다 — 이력의 그 표기는 이 변경의 범위 밖이라 건드리지 않는다.
        OrderStatus previous = order.getStatus();
        order.cancel();

        Order saved = saveOrderPort.save(order);
        historyPort.save(orderId, OrderStatus.CREATED.name(), saved.getStatus().name(), "system", "cancelOrder");
        // 주문 생성 시 차감한 재고를 되돌린다 — 취소 승인·환불 승인 경로와 동일한 역연산.
        // 이 호출이 없으면 직접 취소된 수량만큼 재고가 영구히 판매 불가 상태로 남는다.
        restoreStock(saved);
        applyPointReward(saved, saved.getStatus());
        notifyStatusChanged(saved, previous);
        return saved;
    }

    @Override
    @Transactional
    public Order requestCancellation(Long orderId, String reason, String requestedBy) {
        return changeStatus(orderId, OrderStatus.CANCELLATION_REQUESTED, requestedBy, reason);
    }

    @Override
    @Transactional
    public Order approveCancellation(Long orderId, String reason, String operator) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        // 취소 승인은 사용자가 취소를 신청한(CANCELLATION_REQUESTED) 주문에서만 가능하다.
        if (order.getStatus() != OrderStatus.CANCELLATION_REQUESTED) {
            throw new InvalidOrderStateException(order.getStatus(),
                    "취소 승인은 CANCELLATION_REQUESTED 상태에서만 가능합니다");
        }
        OrderStatus previous = order.getStatus();

        // 1) 승인 단계 전이(감사 흐름 유지): CANCELLATION_REQUESTED → CANCELLATION_APPROVED
        order.transitionTo(OrderStatus.CANCELLATION_APPROVED);
        saveOrderPort.save(order);

        // 2) 결제가 있으면 전액 환불(취소는 배송 전 전액 환불). 환불되면 payment 가 주문을 REFUNDED 로 전이하고
        //    PaymentRefunded 이벤트를 발행한다(→ settlement 역정산). 미결제 주문이면 환불 없이 false.
        //    PG 실패 시 예외 전파 → 트랜잭션 롤백("성공한 경우에만 확정").
        boolean refunded = refundOrderPaymentPort.refundOrderPaymentFullyIfPresent(orderId);

        // 3) 최종 상태 확정 — 환불됐으면 REFUNDED(payment 가 이미 전이, 멱등 no-op), 미결제면 CANCELED.
        Order finalOrder = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        OrderStatus terminal = refunded ? OrderStatus.REFUNDED : OrderStatus.CANCELED;
        if (finalOrder.getStatus() != terminal) {
            finalOrder.transitionTo(terminal);
            finalOrder = saveOrderPort.save(finalOrder);
        }

        // 4) 승인 이력 + 재고 원복(주문 생성 시 차감한 재고는 결제 여부와 무관하게 되돌린다).
        historyPort.save(orderId, previous.name(), finalOrder.getStatus().name(),
                operator == null || operator.isBlank() ? "system" : operator, reason);
        restoreStock(finalOrder);
        applyPointReward(finalOrder, finalOrder.getStatus());
        notifyStatusChanged(finalOrder, previous);
        return finalOrder;
    }

    @Override
    @Transactional
    public Order requestRefund(Long orderId, String reason, String requestedBy) {
        return changeStatus(orderId, OrderStatus.REFUND_REQUESTED, requestedBy, reason);
    }

    @Override
    @Transactional
    public Order approveRefund(Long orderId, String reason, String operator) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        // 환불 승인은 사용자가 환불을 신청한(REFUND_REQUESTED) 주문에서만 가능하다.
        if (order.getStatus() != OrderStatus.REFUND_REQUESTED) {
            throw new InvalidOrderStateException(order.getStatus(),
                    "환불 승인은 REFUND_REQUESTED 상태에서만 가능합니다");
        }
        OrderStatus previous = order.getStatus();

        // 1) 배송 상태 기반 환불 금액 계산 — 배송 시작 후면 배송비를 차감한다(배송 전이면 전액).
        RefundPolicy.RefundOutcome outcome =
                RefundPolicy.forOrder(order.getAmount(), order.getShippingFee(), order.isShipped());

        // 2) 실제 PG 환불 실행. 전액이면 payment 가 주문을 REFUNDED 로 자동 전이하고 PaymentRefunded
        //    이벤트를 발행한다(→ settlement 역정산). 배송비 차감(부분)이면 payment 는 CAPTURED 로 남으므로
        //    3)에서 주문을 명시적으로 REFUNDED 확정한다. PG 실패 시 예외 전파 → 트랜잭션 롤백
        //    ("환불에 성공한 경우에만 주문이 확정").
        if (outcome.deductsShippingFee()) {
            refundOrderPaymentPort.refundOrderPayment(
                    orderId, outcome.refundableAmount(), refundApprovalKey(orderId));
        } else {
            refundOrderPaymentPort.refundOrderPaymentFully(orderId);
        }

        // 3) 환불 성공 후 주문을 REFUNDED 로 확정한다(전액 환불로 이미 전이됐으면 멱등 no-op).
        Order refunded = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (refunded.getStatus() != OrderStatus.REFUNDED) {
            refunded.transitionTo(OrderStatus.REFUNDED);
            refunded = saveOrderPort.save(refunded);
        }

        // 4) 관리자 승인 이력 기록 + 환불된 주문 라인만큼 재고 원복(주문 생성 시 차감의 역연산).
        //    (단건 레거시 주문은 items 가 비어 재고를 차감하지 않았으므로 원복 대상도 없다.)
        historyPort.save(orderId, previous.name(), refunded.getStatus().name(),
                operator == null || operator.isBlank() ? "system" : operator, reason);
        restoreStock(refunded);
        applyPointReward(refunded, refunded.getStatus());
        notifyStatusChanged(refunded, previous);
        if (outcome.deductsShippingFee()) {
            log.info("환불 승인(배송비 차감): orderId={}, 환불액={}, 차감 배송비={}",
                    orderId, outcome.refundableAmount(), outcome.deductedShippingFee());
        }
        return refunded;
    }

    @Override
    @Transactional
    public Order requestExchange(Long orderId, String reason, String requestedBy) {
        return changeStatus(orderId, OrderStatus.EXCHANGE_REQUESTED, requestedBy, reason);
    }

    @Override
    @Transactional
    public Order resumeShippingAfterExchange(Long orderId, String reason, String operator) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        // 교환 재배송은 교환 신청 상태에서만 시작된다. 이 검사가 없으면 아무 주문이나 배송 대기로
        // 되돌릴 수 있는 경로가 생긴다 — 종단(REFUNDED)까지 간 주문에는 전이표가 막지만,
        // 배송 중이던 주문을 배송 대기로 되돌리는 것은 전이표만으로는 걸러지지 않는다.
        if (order.getStatus() != OrderStatus.EXCHANGE_REQUESTED) {
            throw new InvalidOrderStateException(order.getStatus(),
                    "교환 재배송은 EXCHANGE_REQUESTED 상태에서만 시작할 수 있습니다");
        }
        return changeStatus(orderId, OrderStatus.SHIPPING_PENDING, operator, reason);
    }

    private static String refundApprovalKey(Long orderId) {
        return "order-" + orderId + "-refund-approve";
    }

    /**
     * 반품 회수 완료에 따른 재고 원복 — 배송된 주문도 대상이다(물건이 실제로 돌아왔다).
     * 배송 전 취소·환불로 이미 원복된 주문이면 도메인이 빈 목록을 돌려주어 no-op 이 된다.
     */
    @Override
    @Transactional
    public boolean restoreStockOnReturn(Long orderId) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        List<OrderItem> claimed = order.claimStockRestorationOnReturn();
        if (claimed.isEmpty()) {
            log.info("반품 회수 재고 원복 대상 아님(이미 원복됨·라인 없음): orderId={}", orderId);
            return false;
        }
        applyStockRestoration(order, claimed);
        return true;
    }

    /**
     * 취소·환불로 종단에 도달한 주문의 재고를 되돌린다 — <b>재고 원복의 단일 초크포인트</b>.
     *
     * <p>원복 여부는 도메인이 정한다({@link Order#claimStockRestorationOnCancel()}): 배송이 시작된
     * 주문은 물건이 고객 손에 있어 제외되고, 이미 원복된 주문은 두 번 나오지 않는다. 그래서 여러 경로가
     * 겹쳐 호출해도(관리자 환불 승인 + payment 의 REFUNDED 전이) 재고는 한 번만 늘어난다.
     *
     * <p>개별 라인 원복 실패(단종 등)는 조용히 스킵되며(하위 서비스가 예외 대신 로그), 전체 흐름을 막지 않는다.
     */
    private void restoreStock(Order order) {
        applyStockRestoration(order, order.claimStockRestorationOnCancel());
    }

    private void applyStockRestoration(Order order, List<OrderItem> claimedLines) {
        if (claimedLines.isEmpty()) {
            return;
        }
        for (OrderItem item : claimedLines) {
            if (item.getVariantId() != null) {
                increaseVariantStockUseCase.increase(item.getVariantId(), item.getQuantity());
            } else {
                increaseProductStockUseCase.increase(item.getProductId(), item.getQuantity());
            }
        }
        saveOrderPort.save(order);   // 원복 완료 플래그를 영속화해 재기동 후에도 멱등을 유지한다.
        log.info("주문 재고 원복 완료: orderId={}, lines={}", order.getId(), claimedLines.size());
    }

    @Override
    @Transactional
    public Order changeShippingStatus(Long orderId, String status, String reason, String operator) {
        OrderStatus target = OrderStatus.valueOf(status.toUpperCase());
        if (target != OrderStatus.SHIPPING_PENDING
                && target != OrderStatus.IN_TRANSIT
                && target != OrderStatus.DELIVERED) {
            throw new OrderInvariantViolationException("배송 상태로 변경할 수 없는 값입니다: " + status);
        }
        return changeStatus(orderId, target, operator, reason);
    }

    @Override
    @Transactional
    public Order updateStatus(Long orderId, String status) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus target = OrderStatus.valueOf(status);
        OrderStatus previous = order.getStatus();
        order.transitionTo(target);

        Order saved = saveOrderPort.save(order);
        historyPort.save(orderId, previous.name(), saved.getStatus().name(), "system", "updateStatus");
        // payment 가 전액 환불·취소로 주문을 종단에 올린 경우에도 재고를 되돌린다
        // (PATCH /payments/{id}/refund 직접 경로). 관리자 승인 경로와 겹쳐도 도메인 멱등이 이중 원복을 막는다.
        if (target == OrderStatus.REFUNDED || target == OrderStatus.CANCELED) {
            restoreStock(saved);
        }
        applyPointReward(saved, target);
        notifyStatusChanged(saved, previous);
        return saved;
    }

    /**
     * 미결제 주문 취소 — 입금 기한이 지난 결제(payment 컨텍스트)의 요청.
     *
     * <p>{@code CREATED} 만 취소하고 재고를 원복한다. 그 외 상태(결제 완료·이미 취소·환불 등)는
     * <b>예외 없이</b> false 로 알린다 — 잔류 결제 정리가 정상 주문을 건드리면 안 되고, 배치가 한 건 때문에
     * 멈춰서도 안 되기 때문이다. 재취소 요청이 재고를 두 번 되돌리지 않는 멱등성도 이 가드가 보장한다.
     */
    @Override
    @Transactional
    public boolean cancelUnpaidOrder(Long orderId, String reason) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.CREATED) {
            log.info("미결제 취소 대상 아님 — 손대지 않음: orderId={}, status={}", orderId, order.getStatus());
            return false;
        }

        order.transitionTo(OrderStatus.CANCELED);
        Order saved = saveOrderPort.save(order);
        historyPort.save(orderId, OrderStatus.CREATED.name(), saved.getStatus().name(), "system", reason);
        restoreStock(saved);
        applyPointReward(saved, saved.getStatus());
        notifyStatusChanged(saved, OrderStatus.CREATED);   // 위 가드가 이전 상태를 CREATED 로 못박았다.
        return true;
    }

    private Order changeStatus(Long orderId, OrderStatus target, String changedBy, String reason) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        OrderStatus previous = order.getStatus();
        order.transitionTo(target);
        Order saved = saveOrderPort.save(order);
        historyPort.save(orderId, previous.name(), target.name(),
                changedBy == null || changedBy.isBlank() ? "system" : changedBy, reason);
        applyPointReward(saved, target);
        notifyStatusChanged(saved, previous);
        return saved;
    }

    /**
     * 상태 전이에 딸린 포인트 적립·회수.
     *
     * <p>적립 시점은 <b>배송 완료</b>다. 결제 시점에 주면 취소·환불이 잦은 구간에서 회수 부담이
     * 커지고, 별도 "구매확정" 상태가 없는 이 도메인에서는 DELIVERED 가 사실상의 확정 시점이다.
     *
     * <p>취소·환불에서 회수하지 않으면 상품값은 돌려주고 적립은 남는 구멍이 생긴다. 두 연산 모두
     * 멱등이라 여러 경로가 겹쳐 호출해도(관리자 승인 + 결제 환불 콜백) 한 번만 반영된다 —
     * 재고 원복이 같은 이유로 도메인 멱등에 기대는 것과 같은 구조다.
     */
    private void applyPointReward(Order saved, OrderStatus target) {
        if (target == OrderStatus.DELIVERED) {
            orderPointRewardPort.earnOnDelivered(saved);
        } else if (target == OrderStatus.CANCELED || target == OrderStatus.REFUNDED) {
            orderPointRewardPort.revokeOnCanceled(saved);
            // 쿠폰도 같은 시점에 되돌린다. 포인트 회수만 하고 쿠폰을 두면 "환불은 받았는데
            // 1 회용 쿠폰은 소멸"하는 비대칭이 남는다 — 레거시 커머스가 취소 SQL 안에서
            // couponsts 를 미사용으로 되돌리던 처리와 같은 의도다.
            orderCouponRestorePort.restoreOnCanceled(saved.getId(), "주문 " + target.name());
        }
    }

    /**
     * 상태 전이를 고객에게 알린다 — 이 서비스가 오래 안 하던 일.
     *
     * <p>주문 확인 메일 한 통만 나가고 그 뒤로는 배송이 시작돼도, 환불 신청이 접수돼도 아무 통지가
     * 없었다. 알릴 사건인지의 판정은 {@link OrderNotifiableEvent} 가, 채널 팬아웃과 실패 격리는
     * 어댑터가 한다. 여기서는 <b>언제 부를지</b>만 정한다 — {@link #applyPointReward} 를 부르는
     * 자리와 정확히 같은 자리다.
     *
     * <p><b>커밋 후에 보낸다.</b> 트랜잭션 안에서 바로 보내면 뒤이어 롤백된 전이도 고객에게는
     * 이미 통지된 상태가 된다. "환불 접수됐다"는 문자를 받았는데 주문은 그대로인 상황은 되돌릴 수
     * 없다 — 재고·포인트와 달리 발송은 보상 트랜잭션이 없다. 트랜잭션이 없는 호출(단위 테스트 등)
     * 에서는 동기화가 비활성이므로 그 자리에서 보낸다.
     */
    private void notifyStatusChanged(Order saved, OrderStatus previous) {
        OrderNotifiableEvent event = OrderNotifiableEvent.of(previous, saved.getStatus()).orElse(null);
        if (event == null) {
            return;   // 알릴 사건이 아니면 이메일 조회조차 하지 않는다.
        }
        if (!claimNotification(saved.getId(), event)) {
            return;
        }
        String email = loadUserForOrderPort.findEmailById(saved.getUserId()).orElse(null);
        afterCommit(() -> sendOrderNotificationPort.sendStatusChanged(email, saved, previous));
    }

    /**
     * 한 트랜잭션 안에서 같은 (주문, 사건) 조합을 한 번만 통지하도록 선점한다.
     *
     * <p>없으면 환불 한 건에 문자가 두 번 간다. 환불 승인은 PG 환불을 부르고, 결제 컨텍스트가 그
     * 결과로 주문을 {@code REFUNDED} 로 올리며 {@link #updateStatus} 를 다시 태운다 — 재고 원복과
     * 포인트 회수가 <b>도메인 멱등</b> 으로 막고 있는 바로 그 겹침이다. 발송에는 그런 멱등이 없어
     * 여기서 막는다. 커밋 전에는 여러 번 부딪히고 커밋 뒤에 한 번만 나가면 된다.
     */
    @SuppressWarnings("unchecked")
    private static boolean claimNotification(Long orderId, OrderNotifiableEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return true;   // 트랜잭션 밖(단위 테스트 등) — 겹칠 일이 없다.
        }
        Set<String> claimed = (Set<String>) TransactionSynchronizationManager.getResource(NOTIFY_CLAIM_KEY);
        if (claimed == null) {
            claimed = new HashSet<>();
            TransactionSynchronizationManager.bindResource(NOTIFY_CLAIM_KEY, claimed);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(NOTIFY_CLAIM_KEY);
                }
            });
        }
        return claimed.add(orderId + ":" + event);
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
