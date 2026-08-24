package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.out.CancelUnpaidOrderPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 만료 1건 처리기 — 건별 독립 트랜잭션에서 결제를 만료시키고 주문을 취소한다.
 *
 * <p>스캔 시점의 스냅샷을 믿지 않고 <b>락 안에서 권위 재검증</b>한다(환불 경로와 동형).
 */
@ExtendWith(MockitoExtension.class)
class PaymentExpiryProcessorTest {

    @Mock LoadPaymentPort loadPaymentPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock CancelUnpaidOrderPort cancelUnpaidOrderPort;
    @Mock github.lms.lemuel.payment.application.port.out.PointTenderPort pointTenderPort;
    @InjectMocks PaymentExpiryProcessor processor;

    private PaymentDomain pending(Long id, PaymentStatus status) {
        LocalDateTime created = LocalDateTime.of(2026, 8, 1, 9, 0);
        return PaymentDomain.rehydrate(id, 7000L, new BigDecimal("25000"), BigDecimal.ZERO,
                status, "VIRTUAL_ACCOUNT", null, null, created, created);
    }

    /** 가상계좌 + 포인트 선점이 걸린 입금 대기 결제. */
    private PaymentDomain pendingWithHeldPoint(Long id) {
        LocalDateTime created = LocalDateTime.of(2026, 8, 1, 9, 0);
        PaymentDomain payment = pending(id, PaymentStatus.READY);
        payment.replaceTenders(java.util.List.of(
                github.lms.lemuel.payment.domain.PaymentTender.rehydrate(
                        901L, id, github.lms.lemuel.payment.domain.TenderType.VIRTUAL_ACCOUNT,
                        new BigDecimal("20000"), BigDecimal.ZERO, "VA-1",
                        github.lms.lemuel.payment.domain.TenderStatus.AUTHORIZED, 1, created, created),
                github.lms.lemuel.payment.domain.PaymentTender.rehydrate(
                        902L, id, github.lms.lemuel.payment.domain.TenderType.POINT,
                        new BigDecimal("5000"), BigDecimal.ZERO, null,
                        github.lms.lemuel.payment.domain.TenderStatus.AUTHORIZED, 2, created, created)));
        return payment;
    }

    /**
     * 선점을 풀지 않고 만료시키면 고객 포인트가 <b>영영 잠긴다</b> — 주문은 취소돼 사라지고,
     * 그 주문을 근거로 잠긴 잔고를 풀어 줄 사람이 아무도 없다.
     */
    @Test @DisplayName("만료 시 포인트 선점을 함께 푼다 — 사유는 기한 경과")
    void releasesPointHoldOnExpiry() {
        when(loadPaymentPort.loadByIdForUpdate(5L)).thenReturn(Optional.of(pendingWithHeldPoint(5L)));
        when(cancelUnpaidOrderPort.cancelUnpaidOrder(anyLong(), anyString())).thenReturn(true);

        processor.expireAndCancelOrder(5L);

        verify(pointTenderPort).releaseHold(902L, true);
    }

    @Test @DisplayName("포인트 텐더가 없으면 선점 해제를 부르지 않는다")
    void noHoldToReleaseWhenNoPointTender() {
        when(loadPaymentPort.loadByIdForUpdate(6L)).thenReturn(Optional.of(pending(6L, PaymentStatus.READY)));
        when(cancelUnpaidOrderPort.cancelUnpaidOrder(anyLong(), anyString())).thenReturn(true);

        processor.expireAndCancelOrder(6L);

        verify(pointTenderPort, never()).releaseHold(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test @DisplayName("결제를 EXPIRED 로 저장하고 주문을 취소한다")
    void expiresPaymentAndCancelsOrder() {
        when(loadPaymentPort.loadByIdForUpdate(1L)).thenReturn(Optional.of(pending(1L, PaymentStatus.READY)));
        when(cancelUnpaidOrderPort.cancelUnpaidOrder(anyLong(), anyString())).thenReturn(true);

        boolean cancelled = processor.expireAndCancelOrder(1L);

        ArgumentCaptor<PaymentDomain> saved = ArgumentCaptor.forClass(PaymentDomain.class);
        verify(savePaymentPort).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        verify(cancelUnpaidOrderPort).cancelUnpaidOrder(eq(7000L), anyString());
        assertThat(cancelled).isTrue();
    }

    @Test @DisplayName("비관적 락으로 재조회한다 — 스캔 스냅샷을 그대로 쓰지 않는다")
    void reloadsUnderLock() {
        when(loadPaymentPort.loadByIdForUpdate(1L)).thenReturn(Optional.of(pending(1L, PaymentStatus.READY)));
        when(cancelUnpaidOrderPort.cancelUnpaidOrder(anyLong(), anyString())).thenReturn(true);

        processor.expireAndCancelOrder(1L);

        verify(loadPaymentPort).loadByIdForUpdate(1L);
    }

    @Test @DisplayName("주문이 취소 불가 상태면 결제만 만료하고 주문은 건드리지 않는다")
    void expiresPaymentEvenWhenOrderNotCancellable() {
        when(loadPaymentPort.loadByIdForUpdate(2L)).thenReturn(Optional.of(pending(2L, PaymentStatus.READY)));
        when(cancelUnpaidOrderPort.cancelUnpaidOrder(anyLong(), anyString())).thenReturn(false);

        boolean cancelled = processor.expireAndCancelOrder(2L);

        verify(savePaymentPort).save(any(PaymentDomain.class));
        assertThat(cancelled).isFalse();
    }

    @Test @DisplayName("락 안에서 이미 승인된 결제로 밝혀지면 만료하지 않는다(경합 방어)")
    void doesNotExpireIfAuthorizedMeanwhile() {
        when(loadPaymentPort.loadByIdForUpdate(3L)).thenReturn(Optional.of(pending(3L, PaymentStatus.AUTHORIZED)));

        assertThatThrownBy(() -> processor.expireAndCancelOrder(3L))
                .isInstanceOf(InvalidPaymentStateException.class);

        verify(savePaymentPort, never()).save(any());
        verify(cancelUnpaidOrderPort, never()).cancelUnpaidOrder(anyLong(), anyString());
    }

    @Test @DisplayName("결제가 사라졌으면 타입 예외로 알린다")
    void missingPayment() {
        when(loadPaymentPort.loadByIdForUpdate(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.expireAndCancelOrder(9L))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
