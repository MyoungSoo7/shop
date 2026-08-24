package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.WithdrawOrderRequestUseCase;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.LoadOrderStatusHistoryPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 취소·환불 신청 철회 서비스.
 *
 * <p>복귀 상태는 <b>상태 이력이 기록한 사실</b>에서 읽는다. 주문 행에 "신청 직전 상태" 필드를 새로
 * 두지 않는 이유: 같은 사실을 두 곳에 두면 언젠가 어긋나고, 그때 어느 쪽이 맞는지 판단할 근거가 없다.
 * 이력이 없으면 추측하지 않고 실패한다 — 잘못 추측한 복귀는 결제되지 않은 주문을 PAID 로 만들 수 있다
 * (도메인의 {@code restoreTo.canTransitionTo} 검사가 2 차 방어선이다).
 */
@Service
public class WithdrawOrderRequestService implements WithdrawOrderRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(WithdrawOrderRequestService.class);

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final SaveOrderStatusHistoryPort saveHistoryPort;
    private final LoadOrderStatusHistoryPort loadHistoryPort;

    public WithdrawOrderRequestService(LoadOrderPort loadOrderPort,
                                       SaveOrderPort saveOrderPort,
                                       SaveOrderStatusHistoryPort saveHistoryPort,
                                       LoadOrderStatusHistoryPort loadHistoryPort) {
        this.loadOrderPort = loadOrderPort;
        this.saveOrderPort = saveOrderPort;
        this.saveHistoryPort = saveHistoryPort;
        this.loadHistoryPort = loadHistoryPort;
    }

    @Override
    @Transactional
    public Order withdraw(Long orderId, String reason, String operator) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus requested = order.getStatus();
        if (requested != OrderStatus.CANCELLATION_REQUESTED && requested != OrderStatus.REFUND_REQUESTED) {
            throw new InvalidOrderStateException(requested, "철회할 신청이 없습니다");
        }

        OrderStatus restoreTo = loadHistoryPort.findPreviousStatus(orderId, requested)
                .orElseThrow(() -> new InvalidOrderStateException(requested,
                        "신청 직전 상태 이력이 없어 철회할 수 없습니다"));

        order.withdrawRequest(restoreTo);
        Order saved = saveOrderPort.save(order);
        saveHistoryPort.save(orderId, requested.name(), saved.getStatus().name(), operator,
                reason == null || reason.isBlank() ? "신청 철회" : "신청 철회: " + reason);

        log.info("주문 신청 철회: orderId={}, {} → {}", orderId, requested, saved.getStatus());
        return saved;
    }
}
