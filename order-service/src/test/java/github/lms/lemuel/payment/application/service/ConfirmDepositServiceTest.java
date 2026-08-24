package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.out.LoadOrderPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PointTenderPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.PaymentTender;
import github.lms.lemuel.payment.domain.TenderStatus;
import github.lms.lemuel.payment.domain.TenderType;
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;
import github.lms.lemuel.payment.domain.exception.PaymentOwnershipException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 입금 확인 — 돈이 실제로 들어왔을 때 비로소 결제를 확정한다.
 *
 * <p>여기서 처음으로 일어나는 일들이 이 테스트의 대상이다: PG 매입, 포인트 선점 확정, 주문 PAID,
 * {@code payment.captured} 발행. 결제 생성 시점에는 이 중 아무것도 일어나지 않았다.
 *
 * <p>웹훅은 같은 통보를 여러 번 보내는 것이 정상이므로 <b>멱등</b>이 기능의 일부다.
 */
@ExtendWith(MockitoExtension.class)
class ConfirmDepositServiceTest {

    private static final Long PAYMENT_ID = 55L;
    private static final Long ORDER_ID = 700L;
    private static final Long ACTOR = 42L;
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 20, 9, 0);

    @Mock LoadPaymentPort loadPaymentPort;
    @Mock LoadOrderPort loadOrderPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock PgClientPort pgClientPort;
    @Mock UpdateOrderStatusPort updateOrderStatusPort;
    @Mock PublishEventPort publishEventPort;
    @Mock LoadSellerSettlementMetaPort loadSellerSettlementMetaPort;
    @Mock PointTenderPort pointTenderPort;
    @Mock github.lms.lemuel.payment.application.port.out.GiftCardTenderPort giftCardTenderPort;

    private ConfirmDepositService service;

    @BeforeEach
    void setUp() {
        service = new ConfirmDepositService(loadPaymentPort, loadOrderPort, savePaymentPort, pgClientPort,
                updateOrderStatusPort, publishEventPort, loadSellerSettlementMetaPort, pointTenderPort,
                giftCardTenderPort);
        lenient().when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(loadSellerSettlementMetaPort.findByPaymentId(any())).thenReturn(Optional.empty());
    }

    private PaymentDomain pending(PaymentStatus status) {
        PaymentDomain payment = PaymentDomain.rehydrate(PAYMENT_ID, ORDER_ID,
                new BigDecimal("25000"), BigDecimal.ZERO, status,
                "SPLIT:VIRTUAL_ACCOUNT", null, null, CREATED, CREATED);
        payment.replaceTenders(List.of(
                PaymentTender.rehydrate(901L, PAYMENT_ID, TenderType.VIRTUAL_ACCOUNT,
                        new BigDecimal("20000"), BigDecimal.ZERO, "VA-1",
                        TenderStatus.AUTHORIZED, 1, CREATED, CREATED),
                PaymentTender.rehydrate(902L, PAYMENT_ID, TenderType.POINT,
                        new BigDecimal("5000"), BigDecimal.ZERO, null,
                        TenderStatus.AUTHORIZED, 2, CREATED, CREATED)));
        return payment;
    }

    @Test
    @DisplayName("입금이 확인되면 PG 매입·선점 확정·주문 PAID·이벤트가 한 번에 일어난다")
    void confirmsEverything() {
        when(loadPaymentPort.loadByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(pending(PaymentStatus.READY)));

        PaymentDomain result = service.confirmDeposit(PAYMENT_ID, ACTOR, null);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verify(pgClientPort).capture(eq("VA-1"), eq(new BigDecimal("20000")));
        verify(pointTenderPort).captureHold(902L, ACTOR);
        verify(updateOrderStatusPort).updateOrderStatus(ORDER_ID, "PAID");
        verify(publishEventPort).publishPaymentCaptured(
                eq(PAYMENT_ID), eq(ORDER_ID), any(), any(), any(), any(), any());
    }

    /** 웹훅 재전송이 정상 동작이다 — 두 번째 통보가 매입·차감을 다시 일으키면 안 된다. */
    @Test
    @DisplayName("이미 확정된 결제는 멱등 — 매입도 선점 확정도 다시 하지 않는다")
    void alreadyCapturedIsIdempotent() {
        when(loadPaymentPort.loadByIdForUpdate(PAYMENT_ID))
                .thenReturn(Optional.of(pending(PaymentStatus.CAPTURED)));

        PaymentDomain result = service.confirmDeposit(PAYMENT_ID, ACTOR, null);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verify(pgClientPort, never()).capture(anyString(), any());
        verify(pointTenderPort, never()).captureHold(anyLong(), anyLong());
        verify(publishEventPort, never())
                .publishPaymentCaptured(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * 만료 배치가 먼저 이겼다. 여기서 확정하면 취소된 주문이 결제 완료로 되살아나고, 이미 가용으로
     * 돌아간 포인트를 한 번 더 쓰게 된다.
     */
    @Test
    @DisplayName("만료된 결제는 확정할 수 없다 — 배치가 먼저 이긴 경우")
    void expiredCannotBeConfirmed() {
        when(loadPaymentPort.loadByIdForUpdate(PAYMENT_ID))
                .thenReturn(Optional.of(pending(PaymentStatus.EXPIRED)));

        assertThatThrownBy(() -> service.confirmDeposit(PAYMENT_ID, ACTOR, null))
                .isInstanceOf(InvalidPaymentStateException.class);

        verify(pointTenderPort, never()).captureHold(anyLong(), anyLong());
    }

    @Test
    @DisplayName("입금을 기다리는 결제가 아니면 확정 대상이 아니다")
    void nonDepositPaymentRejected() {
        PaymentDomain card = PaymentDomain.rehydrate(PAYMENT_ID, ORDER_ID,
                new BigDecimal("25000"), BigDecimal.ZERO, PaymentStatus.READY,
                "CARD", null, null, CREATED, CREATED);
        when(loadPaymentPort.loadByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(card));

        assertThatThrownBy(() -> service.confirmDeposit(PAYMENT_ID, ACTOR, null))
                .isInstanceOf(PaymentInvariantViolationException.class);
    }

    @Test
    @DisplayName("남의 결제는 확정할 수 없다 — paymentId 만 알면 피해자 포인트가 소진되던 IDOR")
    void rejectsForeignPayment() {
        when(loadPaymentPort.loadByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(pending(PaymentStatus.READY)));
        when(loadOrderPort.loadOrder(ORDER_ID))
                .thenReturn(new LoadOrderPort.OrderInfo(ORDER_ID, 999L, new BigDecimal("25000"), "PENDING"));

        assertThatThrownBy(() -> service.confirmDeposit(PAYMENT_ID, ACTOR, ACTOR))
                .isInstanceOf(PaymentOwnershipException.class);

        // 돈이 한 푼도 움직이지 않아야 한다 — 거절은 매입·선점 확정·주문 전이 <b>이전</b>이다.
        verify(pgClientPort, never()).capture(anyString(), any());
        verify(pointTenderPort, never()).captureHold(anyLong(), anyLong());
        verify(updateOrderStatusPort, never()).updateOrderStatus(anyLong(), anyString());
        verify(publishEventPort, never()).publishPaymentCaptured(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("이미 확정된 남의 결제도 조회되지 않는다 — 멱등 단축 반환이 조회 창구가 되면 안 된다")
    void rejectsForeignPaymentEvenWhenAlreadyCaptured() {
        when(loadPaymentPort.loadByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(pending(PaymentStatus.CAPTURED)));
        when(loadOrderPort.loadOrder(ORDER_ID))
                .thenReturn(new LoadOrderPort.OrderInfo(ORDER_ID, 999L, new BigDecimal("25000"), "PAID"));

        assertThatThrownBy(() -> service.confirmDeposit(PAYMENT_ID, ACTOR, ACTOR))
                .isInstanceOf(PaymentOwnershipException.class);
    }

    @Test
    @DisplayName("주문 소유자를 알 수 없으면 거부한다 (fail-closed)")
    void rejectsWhenOwnerUnknown() {
        when(loadPaymentPort.loadByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(pending(PaymentStatus.READY)));
        when(loadOrderPort.loadOrder(ORDER_ID))
                .thenReturn(new LoadOrderPort.OrderInfo(ORDER_ID, new BigDecimal("25000"), "PENDING"));

        assertThatThrownBy(() -> service.confirmDeposit(PAYMENT_ID, ACTOR, ACTOR))
                .isInstanceOf(PaymentOwnershipException.class);
    }

    @Test
    @DisplayName("본인 주문이면 확정된다 — 대조가 정상 경로를 막지 않는다")
    void allowsOwner() {
        when(loadPaymentPort.loadByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(pending(PaymentStatus.READY)));
        when(loadOrderPort.loadOrder(ORDER_ID))
                .thenReturn(new LoadOrderPort.OrderInfo(ORDER_ID, ACTOR, new BigDecimal("25000"), "PENDING"));

        PaymentDomain result = service.confirmDeposit(PAYMENT_ID, ACTOR, ACTOR);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verify(pointTenderPort).captureHold(902L, ACTOR);
    }
}
