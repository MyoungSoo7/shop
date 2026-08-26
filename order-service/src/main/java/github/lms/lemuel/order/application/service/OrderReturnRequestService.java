package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.in.ProcessOrderReturnUseCase;
import github.lms.lemuel.order.application.port.in.RequestOrderReturnUseCase;
import github.lms.lemuel.order.application.port.in.WithdrawOrderRequestUseCase;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.LoadOrderRefundRoutePort;
import github.lms.lemuel.order.application.port.out.LoadOrderReturnRequestPort;
import github.lms.lemuel.order.application.port.out.SaveOrderReturnRequestPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderReturnRequest;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.RefundAccount;
import github.lms.lemuel.order.domain.ReturnRequestStatus;
import github.lms.lemuel.order.domain.ReturnRequestType;
import github.lms.lemuel.order.domain.ReturnWaybill;
import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.order.domain.exception.InvalidReturnRequestStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 반품·교환·취소 신청 서비스.
 *
 * <p><b>이 서비스는 주문 상태를 직접 바꾸지 않는다.</b> 상태 전이는 전부
 * {@link ChangeOrderStatusUseCase} 를 거친다 — 그쪽이 이력·재고 원복·포인트·통지를 한 자리에서
 * 처리하고 있어서, 여기서 {@code order.transitionTo} 를 부르면 그 부수 효과들만 조용히 빠진다.
 * 이 클래스가 하는 일은 "신청이라는 사실"을 기록하고 그 진행에 맞춰 기존 경로를 호출하는 것이다.
 *
 * <p>거절·철회가 {@link WithdrawOrderRequestUseCase} 를 재사용하는 이유도 같다. 신청 직전 상태는
 * 상태 이력에만 있고, 그걸 읽어 되돌리는 규칙은 이미 거기 있다. 여기서 다시 구현하면 "배송 중이던
 * 주문의 반품을 거절하면 어디로 돌아가는가"에 대한 답이 두 개가 된다.
 */
@Service
public class OrderReturnRequestService implements RequestOrderReturnUseCase, ProcessOrderReturnUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrderReturnRequestService.class);

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderReturnRequestPort saveRequestPort;
    private final LoadOrderReturnRequestPort loadRequestPort;
    private final LoadOrderRefundRoutePort refundRoutePort;
    private final ChangeOrderStatusUseCase changeOrderStatusUseCase;
    private final WithdrawOrderRequestUseCase withdrawOrderRequestUseCase;

    public OrderReturnRequestService(LoadOrderPort loadOrderPort,
                                     SaveOrderReturnRequestPort saveRequestPort,
                                     LoadOrderReturnRequestPort loadRequestPort,
                                     LoadOrderRefundRoutePort refundRoutePort,
                                     ChangeOrderStatusUseCase changeOrderStatusUseCase,
                                     WithdrawOrderRequestUseCase withdrawOrderRequestUseCase) {
        this.loadOrderPort = loadOrderPort;
        this.saveRequestPort = saveRequestPort;
        this.loadRequestPort = loadRequestPort;
        this.refundRoutePort = refundRoutePort;
        this.changeOrderStatusUseCase = changeOrderStatusUseCase;
        this.withdrawOrderRequestUseCase = withdrawOrderRequestUseCase;
    }

    // ───────── 고객 ─────────

    @Override
    @Transactional
    public OrderReturnRequest submit(SubmitCommand command) {
        Order order = loadOrderPort.findById(command.orderId())
                .orElseThrow(() -> new OrderNotFoundException(command.orderId()));

        // 진행 중인 신청이 있으면 새로 받지 않는다. DB 의 부분 유니크 인덱스가 최종 방어선이고,
        // 여기 검사는 동시 요청이 아닌 평범한 재클릭에 사람이 읽을 수 있는 메시지를 주기 위한 것이다.
        loadRequestPort.findOpenByOrderId(command.orderId()).ifPresent(open -> {
            throw new InvalidReturnRequestStateException(open.getStatus(),
                    "이미 처리 중인 " + label(open.getType()) + " 신청이 있습니다");
        });

        ReturnRequestType type = command.type();
        if (!order.getStatus().canTransitionTo(type.requestedOrderStatus())) {
            throw new InvalidOrderStateException(order.getStatus(),
                    label(type) + " 을(를) 신청할 수 없는 상태입니다");
        }

        RefundAccount account = RefundAccount.ofNullable(
                command.refundBankCode(), command.refundAccountNumber(), command.refundAccountHolder());

        OrderReturnRequest request = OrderReturnRequest.open(
                command.orderId(), command.userId(), type,
                command.reasonCode(), command.reasonDetail(),
                account, refundRoutePort.requiresBankRefund(command.orderId()),
                command.requestedBy());

        OrderReturnRequest saved = saveRequestPort.save(request);
        moveOrderToRequested(type, command.orderId(), reasonLine(saved), command.requestedBy());

        log.info("반품·교환 신청 접수: orderId={}, requestId={}, type={}, 계좌첨부={}",
                command.orderId(), saved.getId(), type, saved.getRefundAccount() != null);
        return saved;
    }

    @Override
    @Transactional
    public OrderReturnRequest registerReturnWaybill(Long requestId, String carrier, String trackingNumber,
                                                    String actor) {
        OrderReturnRequest request = load(requestId);
        request.registerReturnWaybill(new ReturnWaybill(carrier, trackingNumber));
        OrderReturnRequest saved = saveRequestPort.save(request);
        log.info("회수 송장 등록: requestId={}, orderId={}, carrier={}", requestId, saved.getOrderId(), carrier);
        return saved;
    }

    @Override
    @Transactional
    public OrderReturnRequest withdraw(Long requestId, String reason, String actor) {
        OrderReturnRequest request = load(requestId);
        request.withdraw(actor);
        OrderReturnRequest saved = saveRequestPort.save(request);
        restoreOrderStatus(saved, reason == null || reason.isBlank() ? "신청 철회" : reason, actor);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderReturnRequest getById(Long requestId) {
        return load(requestId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderReturnRequest> findByOrderId(Long orderId) {
        return loadRequestPort.findAllByOrderId(orderId);
    }

    // ───────── 운영 ─────────

    @Override
    @Transactional
    public OrderReturnRequest approve(Long requestId, String operator) {
        OrderReturnRequest request = load(requestId);
        request.approve(operator);

        if (request.getType() == ReturnRequestType.CANCEL) {
            // 출고 전 취소는 회수할 물건이 없다 — 승인이 곧 완료이고, 환불도 이 자리에서 끝난다.
            changeOrderStatusUseCase.approveCancellation(
                    request.getOrderId(), reasonLine(request), operator);
            request.complete(operator);
        }
        return saveRequestPort.save(request);
    }

    @Override
    @Transactional
    public OrderReturnRequest reject(Long requestId, String reason, String operator) {
        OrderReturnRequest request = load(requestId);
        request.reject(operator, reason);
        OrderReturnRequest saved = saveRequestPort.save(request);
        restoreOrderStatus(saved, reason == null || reason.isBlank() ? "신청 거절" : "신청 거절: " + reason, operator);
        return saved;
    }

    @Override
    @Transactional
    public OrderReturnRequest markCollected(Long requestId, String operator) {
        OrderReturnRequest request = load(requestId);
        request.markCollected(operator);
        OrderReturnRequest saved = saveRequestPort.save(request);

        // 물건이 실제로 돌아온 이 시점이 재고를 되돌리는 유일한 근거다(배송 후 환불은 물건이
        // 고객 손에 있어 원복하지 않는다). 이미 원복된 주문이면 멱등 no-op.
        changeOrderStatusUseCase.restoreStockOnReturn(saved.getOrderId());
        return saved;
    }

    @Override
    @Transactional
    public OrderReturnRequest shipExchange(Long requestId, String carrier, String trackingNumber,
                                           String operator) {
        OrderReturnRequest request = load(requestId);
        request.shipExchange(new ReturnWaybill(carrier, trackingNumber), operator);
        OrderReturnRequest saved = saveRequestPort.save(request);

        changeOrderStatusUseCase.resumeShippingAfterExchange(
                saved.getOrderId(), "교환품 재배송 (송장 " + trackingNumber + ")", operator);
        log.info("교환 재배송: requestId={}, orderId={}, carrier={}", requestId, saved.getOrderId(), carrier);
        return saved;
    }

    @Override
    @Transactional
    public OrderReturnRequest completeRefund(Long requestId, String operator) {
        OrderReturnRequest request = load(requestId);
        if (request.getType() == ReturnRequestType.EXCHANGE) {
            throw new OrderInvariantViolationException("교환 신청은 재배송으로 완료합니다");
        }
        // PG 로 되돌릴 수 없는 결제인데 계좌가 없으면 "환불 완료"라고 적을 수 없다 — 그렇게 적으면
        // 고객은 완료 안내를 받고 입금은 영원히 오지 않는다. 판단 근거는 접수 시점에 굳힌 값이다
        // (여기서 결제를 다시 물으면 그 사이 결제가 바뀐 신청이 계좌 없이 통과할 수 있다).
        if (request.awaitsRefundAccount()) {
            throw new OrderInvariantViolationException(
                    "계좌 환불 대상인데 환불 계좌가 없습니다 — 계좌를 먼저 등록해 주세요");
        }

        changeOrderStatusUseCase.approveRefund(request.getOrderId(), reasonLine(request), operator);
        request.complete(operator);
        return saveRequestPort.save(request);
    }

    @Override
    @Transactional
    public OrderReturnRequest changeRefundAccount(Long requestId, String bankCode, String accountNumber,
                                                  String holderName, String operator) {
        OrderReturnRequest request = load(requestId);
        request.changeRefundAccount(new RefundAccount(bankCode, accountNumber, holderName));
        OrderReturnRequest saved = saveRequestPort.save(request);
        log.info("환불 계좌 변경: requestId={}, orderId={}, 계좌={}",
                requestId, saved.getOrderId(), saved.getRefundAccount().maskedAccountNumber());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderReturnRequest> queue(Collection<ReturnRequestStatus> statuses, int limit) {
        Collection<ReturnRequestStatus> effective = (statuses == null || statuses.isEmpty())
                ? List.of(ReturnRequestStatus.REQUESTED, ReturnRequestStatus.APPROVED,
                          ReturnRequestStatus.COLLECTED)
                : statuses;
        return loadRequestPort.findByStatuses(effective, limit);
    }

    // ───────── 내부 ─────────

    private OrderReturnRequest load(Long requestId) {
        return loadRequestPort.findById(requestId)
                .orElseThrow(() -> new OrderNotFoundException(requestId));
    }

    private void moveOrderToRequested(ReturnRequestType type, Long orderId, String reason, String actor) {
        switch (type) {
            case CANCEL -> changeOrderStatusUseCase.requestCancellation(orderId, reason, actor);
            case RETURN -> changeOrderStatusUseCase.requestRefund(orderId, reason, actor);
            case EXCHANGE -> changeOrderStatusUseCase.requestExchange(orderId, reason, actor);
        }
    }

    /**
     * 거절·철회 후 주문을 신청 직전 상태로 되돌린다.
     *
     * <p>주문이 이미 신청 상태를 벗어났으면(운영자가 다른 경로로 먼저 처리) 되돌릴 것이 없다 —
     * 신청 레코드만 종단으로 남기고 조용히 지나간다. 여기서 예외를 던지면 "이미 처리된 주문의
     * 신청을 거절할 수 없다"가 되어 대기열에 열린 신청이 영원히 남는다.
     */
    private void restoreOrderStatus(OrderReturnRequest request, String reason, String actor) {
        Order order = loadOrderPort.findById(request.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(request.getOrderId()));
        OrderStatus current = order.getStatus();
        if (current != request.getType().requestedOrderStatus()) {
            log.info("신청 종료 시 주문이 이미 신청 상태가 아님(복귀 생략): orderId={}, status={}",
                    request.getOrderId(), current);
            return;
        }
        withdrawOrderRequestUseCase.withdraw(request.getOrderId(), reason, actor);
    }

    private static String reasonLine(OrderReturnRequest request) {
        String detail = request.getReasonDetail();
        return detail == null ? request.getReasonCode() : request.getReasonCode() + ": " + detail;
    }

    private static String label(ReturnRequestType type) {
        return switch (type) {
            case CANCEL -> "취소";
            case RETURN -> "반품";
            case EXCHANGE -> "교환";
        };
    }
}
