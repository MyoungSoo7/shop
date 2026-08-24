package github.lms.lemuel.payment.application;
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;

import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.SaveRefundPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.application.service.RefundLifecycle;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.exception.MissingIdempotencyKeyException;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.payment.domain.exception.RefundExceedsPaymentException;
import github.lms.lemuel.payment.domain.exception.RefundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RefundPaymentUseCase 테스트.
 *
 * <p>환불 시도 이력은 락 획득 전에 {@link RefundLifecycle#begin} 으로 REQUESTED 를 독립 커밋하고,
 * 성공 시 본 트랜잭션에서 COMPLETED 확정, PG 실패 시 {@link RefundLifecycle#fail} 로 FAILED 를 남긴다.
 * 정산 조정은 settlement-service 가 PaymentRefunded Kafka 이벤트를 컨슘해 처리하므로
 * publishPaymentRefunded 호출로 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class RefundPaymentUseCaseTest {

    @Mock LoadPaymentPort loadPaymentPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock PgClientPort pgClientPort;
    @Mock UpdateOrderStatusPort updateOrderStatusPort;
    @Mock PublishEventPort publishEventPort;
    @Mock LoadRefundPort loadRefundPort;
    @Mock SaveRefundPort saveRefundPort;
    @Mock RefundLifecycle refundLifecycle;
    // 전액 환불 시 현금영수증 취소가 딸려 나간다(카드 결제 시나리오라 실제로는 no-op).
    @Mock github.lms.lemuel.payment.application.port.in.CashReceiptUseCase cashReceiptUseCase;
    @InjectMocks RefundPaymentUseCase refundPaymentUseCase;

    private PaymentDomain capturedPayment() {
        return PaymentDomain.rehydrate(1L, 10L, new BigDecimal("50000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "pg-tx-123", null, null, null);
    }

    private PaymentDomain partiallyRefundedPayment(BigDecimal alreadyRefunded) {
        return PaymentDomain.rehydrate(1L, 10L, new BigDecimal("50000"), alreadyRefunded,
                PaymentStatus.CAPTURED, "CARD", "pg-tx-123", null, null, null);
    }

    /** begin() 이 REQUESTED 이력을 만들어 반환하는 동작을 흉내낸다(id 부여). */
    private void stubBegin() {
        when(refundLifecycle.begin(any(), any(), any(), any())).thenAnswer(inv -> {
            Refund r = Refund.request(inv.getArgument(0), inv.getArgument(1),
                    inv.getArgument(2), inv.getArgument(3));
            r.assignId(999L);
            return r;
        });
    }

    private void stubSaveRefund() {
        when(saveRefundPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test @DisplayName("전액 환불: amount=null 이면 결제 전액이 환불되고 Payment 는 REFUNDED")
    void fullRefund_defaultAmount() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(loadPaymentPort.loadByIdForUpdate(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubBegin();
        stubSaveRefund();

        PaymentDomain result = refundPaymentUseCase.refundPayment(1L);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(refundLifecycle).begin(eq(1L), any(), eq("payment-1-full"), any());
        verify(pgClientPort).refund("pg-tx-123", new BigDecimal("50000"), "payment-1-full");
        verify(updateOrderStatusPort).updateOrderStatus(10L, "REFUNDED");
        verify(publishEventPort).publishPaymentRefunded(eq(1L), eq(10L), any(), any(), any());
    }

    @Test @DisplayName("부분 환불: amount < 결제금액이면 Payment 는 CAPTURED 유지, 주문 상태 미변경")
    void partialRefund_stillCaptured() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(loadPaymentPort.loadByIdForUpdate(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubBegin();
        stubSaveRefund();

        PaymentDomain result = refundPaymentUseCase.refundPayment(
                1L, new BigDecimal("20000"), "partial-key-1");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        // BigDecimal 은 equals 가 scale 까지 비교한다 — 20000 과 20000.00 이 불일치로 잡힌다.
        // 금액이 맞는데 테스트가 깨지는 걸 막으려면 값 비교(compareTo)를 써야 한다.
        assertThat(result.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("20000"));
        verify(pgClientPort).refund("pg-tx-123", new BigDecimal("20000"), "partial-key-1");
        verify(updateOrderStatusPort, never()).updateOrderStatus(any(), any());
        verify(publishEventPort).publishPaymentRefunded(eq(1L), eq(10L), any(), any(), any());
    }

    @Test @DisplayName("부분 환불로 전액 도달 시 Payment REFUNDED + 주문 상태 REFUNDED")
    void partialRefund_reachesFull_transitionsToRefunded() {
        PaymentDomain payment = partiallyRefundedPayment(new BigDecimal("30000"));
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(loadPaymentPort.loadByIdForUpdate(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubBegin();
        stubSaveRefund();

        PaymentDomain result = refundPaymentUseCase.refundPayment(
                1L, new BigDecimal("20000"), "partial-key-2");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(updateOrderStatusPort).updateOrderStatus(10L, "REFUNDED");
    }

    @Test @DisplayName("부분 환불에 idempotencyKey 가 없으면 MissingIdempotencyKeyException (begin 이전에 거부)")
    void partialRefund_requiresIdempotencyKey() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(
                1L, new BigDecimal("10000"), null))
                .isInstanceOf(MissingIdempotencyKeyException.class);
        verify(refundLifecycle, never()).begin(any(), any(), any(), any());
        verify(pgClientPort, never()).refund(any(), any(), any());
    }

    @Test @DisplayName("잔여 환불 가능 금액 초과 시 RefundExceedsPaymentException (락/begin 이전에 거부)")
    void refund_exceedsRefundable() {
        PaymentDomain payment = partiallyRefundedPayment(new BigDecimal("40000"));
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(
                1L, new BigDecimal("20000"), "too-much"))
                .isInstanceOf(RefundExceedsPaymentException.class);
        verify(refundLifecycle, never()).begin(any(), any(), any(), any());
        verify(pgClientPort, never()).refund(any(), any(), any());
    }

    @Test @DisplayName("결제 미존재 시 예외")
    void refund_paymentNotFound() {
        when(loadPaymentPort.loadById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(999L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test @DisplayName("이미 REFUNDED 면 PG 호출 없이 멱등 반환")
    void refund_alreadyRefunded_noPgCall() {
        PaymentDomain payment = PaymentDomain.rehydrate(1L, 10L, new BigDecimal("50000"), new BigDecimal("50000"),
                PaymentStatus.REFUNDED, "CARD", "pg-tx-123", null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));

        refundPaymentUseCase.refundPayment(1L);

        verify(pgClientPort, never()).refund(any(), any(), any());
        verify(refundLifecycle, never()).begin(any(), any(), any(), any());
        verify(saveRefundPort, never()).save(any());
    }

    @Test @DisplayName("동일 idempotencyKey 로 COMPLETED Refund 가 있으면 PG 재호출 없음")
    void refund_existingCompleted_noPgCall() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        Refund existing = Refund.request(1L, new BigDecimal("50000"), "payment-1-full", "x");
        existing.assignId(999L);
        existing.markCompleted();
        when(loadRefundPort.findByPaymentIdAndIdempotencyKey(1L, "payment-1-full"))
                .thenReturn(Optional.of(existing));

        refundPaymentUseCase.refundPayment(1L);

        verify(pgClientPort, never()).refund(any(), any(), any());
        verify(refundLifecycle, never()).begin(any(), any(), any(), any());
        verify(saveRefundPort, never()).save(any());
    }

    @Test @DisplayName("PG 환불 실패 시 RefundException 변환 + FAILED 이력 기록 + payment/주문 미변경 (롤백)")
    void refund_pgFailure_recordsFailedAndDoesNotMutate() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(loadPaymentPort.loadByIdForUpdate(1L)).thenReturn(Optional.of(payment));
        stubBegin();
        // PG 가 원시 런타임 예외를 던지는 상황 (네트워크/5xx 등)
        doThrow(new RuntimeException("PG 504 timeout"))
                .when(pgClientPort).refund(eq("pg-tx-123"), any(), any());

        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(1L))
                .isInstanceOf(RefundException.class)
                .hasMessageContaining("paymentId=1");

        // 실패 이력은 독립 트랜잭션(fail)으로 기록된다.
        verify(refundLifecycle).fail(eq(999L), any());
        // payment 상태/누적금액 변경 없음
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        // 주문 상태 전이/환불 이벤트 발행이 일어나지 않음
        verify(updateOrderStatusPort, never()).updateOrderStatus(any(), any());
        verify(publishEventPort, never()).publishPaymentRefunded(any(), any(), any(), any(), any());
    }

    @Test @DisplayName("재시도 성공: 기존 FAILED 이력을 재사용해 begin 없이 COMPLETED 로 확정")
    void retrySuccess_reusesFailedRefund() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(loadPaymentPort.loadByIdForUpdate(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubSaveRefund();
        Refund failed = Refund.request(1L, new BigDecimal("50000"), "payment-1-full", "FULL_REFUND");
        failed.assignId(999L);
        failed.markFailed("이전 PG 실패");
        when(loadRefundPort.findByPaymentIdAndIdempotencyKey(1L, "payment-1-full"))
                .thenReturn(Optional.of(failed));

        // 스케줄러가 저장된 금액·키로 재호출
        PaymentDomain result = refundPaymentUseCase.refundPayment(
                1L, new BigDecimal("50000"), "payment-1-full");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(failed.getStatus()).isEqualTo(Refund.Status.COMPLETED);
        verify(refundLifecycle, never()).begin(any(), any(), any(), any());
        verify(pgClientPort).refund("pg-tx-123", new BigDecimal("50000"), "payment-1-full");
        verify(publishEventPort).publishPaymentRefunded(eq(1L), eq(10L), any(), any(), any());
    }

    @Test @DisplayName("결제가 이미 전액 환불된 상태에서 남은 FAILED 재시도는 적용 불가로 재시도 소진 고정")
    void retry_paymentAlreadyRefunded_abandonsRetry() {
        PaymentDomain payment = PaymentDomain.rehydrate(1L, 10L, new BigDecimal("50000"), new BigDecimal("50000"),
                PaymentStatus.REFUNDED, "CARD", "pg-tx-123", null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        Refund failed = Refund.request(1L, new BigDecimal("20000"), "partial-key-x", "PARTIAL_REFUND");
        failed.assignId(999L);
        failed.markFailed("이전 PG 실패");
        when(loadRefundPort.findByPaymentIdAndIdempotencyKey(1L, "partial-key-x"))
                .thenReturn(Optional.of(failed));
        stubSaveRefund();

        PaymentDomain result = refundPaymentUseCase.refundPayment(
                1L, new BigDecimal("20000"), "partial-key-x");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(failed.isRetryExhausted()).isTrue();
        assertThat(failed.getNextRetryAt()).isNull();
        verify(pgClientPort, never()).refund(any(), any(), any());
    }

    @Test @DisplayName("CAPTURED 가 아닌 상태에서는 환불 시도 시 예외 + PG 호출 없음")
    void refund_notCaptured_throwsBeforePgCall() {
        PaymentDomain payment = PaymentDomain.rehydrate(1L, 10L, new BigDecimal("50000"), BigDecimal.ZERO,
                PaymentStatus.READY, "CARD", null, null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(1L))
                .isInstanceOf(InvalidPaymentStateException.class);
        verify(refundLifecycle, never()).begin(any(), any(), any(), any());
        verify(pgClientPort, never()).refund(any(), any(), any());
    }
}
