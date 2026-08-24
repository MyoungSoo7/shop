package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.LoadOrderStatusHistoryPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WithdrawOrderRequestService — 취소·환불 신청 철회")
class WithdrawOrderRequestServiceTest {

    private LoadOrderPort loadOrderPort;
    private SaveOrderPort saveOrderPort;
    private SaveOrderStatusHistoryPort saveHistoryPort;
    private LoadOrderStatusHistoryPort loadHistoryPort;
    private WithdrawOrderRequestService service;

    @BeforeEach
    void setUp() {
        loadOrderPort = mock(LoadOrderPort.class);
        saveOrderPort = mock(SaveOrderPort.class);
        saveHistoryPort = mock(SaveOrderStatusHistoryPort.class);
        loadHistoryPort = mock(LoadOrderStatusHistoryPort.class);
        service = new WithdrawOrderRequestService(loadOrderPort, saveOrderPort,
                saveHistoryPort, loadHistoryPort);
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Order order(Long id, OrderStatus... path) {
        Order o = Order.create(1L, 2L, new BigDecimal("10000"));
        o.assignId(id);
        for (OrderStatus s : path) {
            o.transitionTo(s);
        }
        when(loadOrderPort.findById(id)).thenReturn(Optional.of(o));
        return o;
    }

    @Test
    @DisplayName("이력이 기록한 직전 상태로 되돌린다")
    void restoresPreviousStatusFromHistory() {
        Order o = order(1L, OrderStatus.PAID, OrderStatus.SHIPPING_PENDING,
                OrderStatus.IN_TRANSIT, OrderStatus.REFUND_REQUESTED);
        when(loadHistoryPort.findPreviousStatus(1L, OrderStatus.REFUND_REQUESTED))
                .thenReturn(Optional.of(OrderStatus.IN_TRANSIT));

        Order result = service.withdraw(1L, "마음이 바뀜", "buyer");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.IN_TRANSIT);
        verify(saveHistoryPort).save(eq(1L), eq("REFUND_REQUESTED"), eq("IN_TRANSIT"),
                eq("buyer"), anyString());
    }

    @Test
    @DisplayName("결제 전 취소 신청 철회는 CREATED 로 돌아간다")
    void restoresCreated() {
        order(2L, OrderStatus.CANCELLATION_REQUESTED);
        when(loadHistoryPort.findPreviousStatus(2L, OrderStatus.CANCELLATION_REQUESTED))
                .thenReturn(Optional.of(OrderStatus.CREATED));

        assertThat(service.withdraw(2L, null, "buyer").getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("직전 상태 이력이 없으면 추측하지 않고 실패한다")
    void noHistoryFails() {
        order(3L, OrderStatus.PAID, OrderStatus.REFUND_REQUESTED);
        when(loadHistoryPort.findPreviousStatus(3L, OrderStatus.REFUND_REQUESTED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(3L, "r", "buyer"))
                .isInstanceOf(InvalidOrderStateException.class);
        verify(saveOrderPort, never()).save(any());
    }

    @Test
    @DisplayName("신청 상태가 아니면 철회할 것이 없다")
    void notRequested() {
        order(4L, OrderStatus.PAID);

        assertThatThrownBy(() -> service.withdraw(4L, "r", "buyer"))
                .isInstanceOf(InvalidOrderStateException.class);
        verify(loadHistoryPort, never()).findPreviousStatus(anyLong(), any());
    }

    @Test
    @DisplayName("없는 주문이면 404")
    void notFound() {
        when(loadOrderPort.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(99L, "r", "buyer"))
                .isInstanceOf(OrderNotFoundException.class);
    }
}
