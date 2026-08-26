package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.in.RequestOrderReturnUseCase.SubmitCommand;
import github.lms.lemuel.order.application.port.in.WithdrawOrderRequestUseCase;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.LoadOrderRefundRoutePort;
import github.lms.lemuel.order.application.port.out.LoadOrderReturnRequestPort;
import github.lms.lemuel.order.application.port.out.SaveOrderReturnRequestPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderReturnRequest;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.ReturnRequestStatus;
import github.lms.lemuel.order.domain.ReturnRequestType;
import github.lms.lemuel.order.domain.ReturnWaybill;
import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.order.domain.exception.InvalidReturnRequestStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

@DisplayName("OrderReturnRequestService — 반품·교환 신청 처리")
class OrderReturnRequestServiceTest {

    private LoadOrderPort loadOrderPort;
    private SaveOrderReturnRequestPort saveRequestPort;
    private LoadOrderReturnRequestPort loadRequestPort;
    private LoadOrderRefundRoutePort refundRoutePort;
    private ChangeOrderStatusUseCase changeOrderStatusUseCase;
    private WithdrawOrderRequestUseCase withdrawOrderRequestUseCase;
    private OrderReturnRequestService service;

    @BeforeEach
    void setUp() {
        loadOrderPort = mock(LoadOrderPort.class);
        saveRequestPort = mock(SaveOrderReturnRequestPort.class);
        loadRequestPort = mock(LoadOrderReturnRequestPort.class);
        refundRoutePort = mock(LoadOrderRefundRoutePort.class);
        changeOrderStatusUseCase = mock(ChangeOrderStatusUseCase.class);
        withdrawOrderRequestUseCase = mock(WithdrawOrderRequestUseCase.class);
        service = new OrderReturnRequestService(loadOrderPort, saveRequestPort, loadRequestPort,
                refundRoutePort, changeOrderStatusUseCase, withdrawOrderRequestUseCase);

        when(saveRequestPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadRequestPort.findOpenByOrderId(anyLong())).thenReturn(Optional.empty());
    }

    private Order order(Long id, OrderStatus... path) {
        Order o = Order.create(2L, 3L, new BigDecimal("10000"));
        o.assignId(id);
        for (OrderStatus s : path) {
            o.transitionTo(s);
        }
        when(loadOrderPort.findById(id)).thenReturn(Optional.of(o));
        return o;
    }

    private SubmitCommand command(ReturnRequestType type) {
        return new SubmitCommand(1L, 2L, type, "DEFECT", "찍힘", null, null, null, "buyer");
    }

    /** id 가 이미 있는(복원된) 신청이면 {@code id} 에 null 을 준다. */
    private OrderReturnRequest stored(OrderReturnRequest request, Long id) {
        if (id != null) {
            request.assignId(id);
        }
        when(loadRequestPort.findById(request.getId())).thenReturn(Optional.of(request));
        return request;
    }

    @Nested
    @DisplayName("접수")
    class Submitting {

        @Test
        @DisplayName("교환 신청은 주문을 EXCHANGE_REQUESTED 로 옮긴다")
        void exchangeMovesOrder() {
            order(1L, OrderStatus.PAID, OrderStatus.SHIPPING_PENDING, OrderStatus.IN_TRANSIT,
                    OrderStatus.DELIVERED);

            OrderReturnRequest created = service.submit(command(ReturnRequestType.EXCHANGE));

            assertThat(created.getType()).isEqualTo(ReturnRequestType.EXCHANGE);
            verify(changeOrderStatusUseCase).requestExchange(eq(1L), anyString(), eq("buyer"));
            verify(changeOrderStatusUseCase, never()).requestRefund(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("진행 중인 신청이 있으면 새로 받지 않는다")
        void rejectsSecondOpenRequest() {
            order(1L, OrderStatus.PAID, OrderStatus.SHIPPING_PENDING, OrderStatus.IN_TRANSIT,
                    OrderStatus.DELIVERED);
            when(loadRequestPort.findOpenByOrderId(1L)).thenReturn(Optional.of(
                    OrderReturnRequest.open(1L, 2L, ReturnRequestType.RETURN, "DEFECT", null,
                            null, false, "buyer")));

            assertThatThrownBy(() -> service.submit(command(ReturnRequestType.EXCHANGE)))
                    .isInstanceOf(InvalidReturnRequestStateException.class);
            verify(saveRequestPort, never()).save(any());
        }

        @Test
        @DisplayName("갈 수 없는 상태면 신청 레코드를 만들지 않는다")
        void refusesImpossibleTransition() {
            order(1L); // CREATED — 아직 결제 전

            assertThatThrownBy(() -> service.submit(command(ReturnRequestType.RETURN)))
                    .isInstanceOf(InvalidOrderStateException.class);
            verify(saveRequestPort, never()).save(any());
        }

        @Test
        @DisplayName("무통장 주문은 계좌 없이 신청되지 않는다")
        void depositOrderNeedsAccount() {
            order(1L, OrderStatus.PAID, OrderStatus.SHIPPING_PENDING, OrderStatus.IN_TRANSIT,
                    OrderStatus.DELIVERED);
            when(refundRoutePort.requiresBankRefund(1L)).thenReturn(true);

            assertThatThrownBy(() -> service.submit(command(ReturnRequestType.RETURN)))
                    .isInstanceOf(OrderInvariantViolationException.class)
                    .hasMessageContaining("계좌");
            verify(changeOrderStatusUseCase, never()).requestRefund(anyLong(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("처리")
    class Processing {

        private OrderReturnRequest open(ReturnRequestType type) {
            return stored(OrderReturnRequest.open(1L, 2L, type, "DEFECT", null, null, false, "buyer"), 7L);
        }

        @Test
        @DisplayName("출고 전 취소는 승인이 곧 환불이자 완료다")
        void cancelApprovalCompletes() {
            open(ReturnRequestType.CANCEL);

            OrderReturnRequest result = service.approve(7L, "admin");

            assertThat(result.getStatus()).isEqualTo(ReturnRequestStatus.COMPLETED);
            verify(changeOrderStatusUseCase).approveCancellation(eq(1L), anyString(), eq("admin"));
        }

        @Test
        @DisplayName("반품 승인은 아직 환불하지 않는다 — 물건이 돌아온 뒤다")
        void returnApprovalDoesNotRefund() {
            open(ReturnRequestType.RETURN);

            OrderReturnRequest result = service.approve(7L, "admin");

            assertThat(result.getStatus()).isEqualTo(ReturnRequestStatus.APPROVED);
            verify(changeOrderStatusUseCase, never()).approveRefund(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("회수 확인 시점에 재고를 되돌린다")
        void collectRestoresStock() {
            OrderReturnRequest request = open(ReturnRequestType.RETURN);
            request.registerReturnWaybill(new ReturnWaybill("CJ", "111"));
            service.approve(7L, "admin");

            service.markCollected(7L, "admin");

            verify(changeOrderStatusUseCase).restoreStockOnReturn(1L);
        }

        @Test
        @DisplayName("교환 재배송은 주문을 배송 흐름으로 되돌린다")
        void exchangeResumesShipping() {
            OrderReturnRequest request = open(ReturnRequestType.EXCHANGE);
            request.registerReturnWaybill(new ReturnWaybill("CJ", "111"));
            service.approve(7L, "admin");
            service.markCollected(7L, "admin");

            OrderReturnRequest result = service.shipExchange(7L, "CJ", "222", "admin");

            assertThat(result.getStatus()).isEqualTo(ReturnRequestStatus.COMPLETED);
            verify(changeOrderStatusUseCase).resumeShippingAfterExchange(eq(1L), anyString(), eq("admin"));
        }

        @Test
        @DisplayName("계좌 환불 대상인데 계좌가 없으면 환불 완료로 적지 않는다")
        void refundBlockedWithoutAccount() {
            // 접수 때는 계좌를 냈다가 운영자가 지울 수는 없으므로, 계좌 없이 접수된 무통장 신청은
            // 만들 수 없다 — 대신 복원 경로로 그 상태(요구됨 + 계좌 없음)를 그대로 세운다.
            stored(OrderReturnRequest.restore(7L, 1L, 2L, ReturnRequestType.RETURN,
                    ReturnRequestStatus.APPROVED, "DEFECT", null, null, null, null,
                    "buyer", "admin", null,
                    LocalDateTime.now(), LocalDateTime.now(), null, null, null, LocalDateTime.now(),
                    true), null);

            assertThatThrownBy(() -> service.completeRefund(7L, "admin"))
                    .isInstanceOf(OrderInvariantViolationException.class);
            verify(changeOrderStatusUseCase, never()).approveRefund(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("교환 신청은 환불 경로로 끝낼 수 없다")
        void exchangeCannotRefund() {
            open(ReturnRequestType.EXCHANGE);

            assertThatThrownBy(() -> service.completeRefund(7L, "admin"))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("거절·철회")
    class Closing {

        @Test
        @DisplayName("거절하면 주문 상태를 신청 직전으로 되돌린다")
        void rejectRestoresOrder() {
            order(1L, OrderStatus.PAID, OrderStatus.SHIPPING_PENDING, OrderStatus.IN_TRANSIT,
                    OrderStatus.DELIVERED, OrderStatus.REFUND_REQUESTED);
            stored(OrderReturnRequest.open(1L, 2L, ReturnRequestType.RETURN, "DEFECT", null,
                    null, false, "buyer"), 7L);

            OrderReturnRequest result = service.reject(7L, "사용 흔적", "admin");

            assertThat(result.getStatus()).isEqualTo(ReturnRequestStatus.REJECTED);
            verify(withdrawOrderRequestUseCase).withdraw(eq(1L), anyString(), eq("admin"));
        }

        @Test
        @DisplayName("주문이 이미 신청 상태를 벗어났으면 되돌리지 않는다 — 신청만 닫는다")
        void skipsRestoreWhenOrderMovedOn() {
            order(1L, OrderStatus.PAID, OrderStatus.SHIPPING_PENDING, OrderStatus.IN_TRANSIT,
                    OrderStatus.DELIVERED, OrderStatus.REFUND_REQUESTED, OrderStatus.REFUNDED);
            stored(OrderReturnRequest.open(1L, 2L, ReturnRequestType.RETURN, "DEFECT", null,
                    null, false, "buyer"), 7L);

            OrderReturnRequest result = service.reject(7L, "이미 처리됨", "admin");

            assertThat(result.getStatus()).isEqualTo(ReturnRequestStatus.REJECTED);
            verify(withdrawOrderRequestUseCase, never()).withdraw(anyLong(), anyString(), anyString());
        }
    }
}
