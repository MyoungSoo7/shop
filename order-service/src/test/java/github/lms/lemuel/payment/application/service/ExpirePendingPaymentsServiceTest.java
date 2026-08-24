package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase.ExpiryReport;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 미입금 결제 자동 만료 배치 유스케이스.
 *
 * <p>입금이 오지 않은 가상계좌·무통장 결제를 만료시키고, 그 주문을 취소해 재고를 되돌린다.
 * 배치이므로 <b>한 건의 실패가 나머지를 막지 않아야 하고</b>, 실패를 조용히 삼켜서도 안 된다(카운터로 드러낸다).
 */
@ExtendWith(MockitoExtension.class)
class ExpirePendingPaymentsServiceTest {

    private static final Duration TTL = Duration.ofHours(48);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 3, 0);

    @Mock LoadPaymentPort loadPaymentPort;
    @Mock PaymentExpiryProcessor processor;

    private ExpirePendingPaymentsService service;

    @BeforeEach
    void setUp() {
        service = new ExpirePendingPaymentsService(loadPaymentPort, processor, TTL, 100);
    }

    private PaymentDomain pending(Long id, String method, LocalDateTime createdAt) {
        return PaymentDomain.rehydrate(id, 900L + id, new BigDecimal("10000"), BigDecimal.ZERO,
                PaymentStatus.READY, method, null, null, createdAt, createdAt);
    }

    @Test @DisplayName("만료 대상이 없으면 아무 것도 처리하지 않는다")
    void noCandidates_noop() {
        when(loadPaymentPort.findPendingCreatedBefore(any(), anyInt())).thenReturn(List.of());

        ExpiryReport report = service.expireDue(NOW, false);

        verifyNoInteractions(processor);
        assertThat(report.scanned()).isZero();
        assertThat(report.expired()).isZero();
    }

    @Test @DisplayName("기한 지난 가상계좌 결제를 만료 처리한다")
    void expiresDueVirtualAccountPayment() {
        PaymentDomain due = pending(1L, "VIRTUAL_ACCOUNT", NOW.minusHours(49));
        when(loadPaymentPort.findPendingCreatedBefore(any(), anyInt())).thenReturn(List.of(due));
        when(processor.expireAndCancelOrder(due.getId())).thenReturn(true);

        ExpiryReport report = service.expireDue(NOW, false);

        verify(processor).expireAndCancelOrder(due.getId());
        assertThat(report.scanned()).isEqualTo(1);
        assertThat(report.expired()).isEqualTo(1);
        assertThat(report.failed()).isZero();
    }

    @Test @DisplayName("조회 컷오프는 now − TTL 이다")
    void queriesWithTtlCutoff() {
        when(loadPaymentPort.findPendingCreatedBefore(NOW.minus(TTL), 100)).thenReturn(List.of());

        service.expireDue(NOW, false);

        verify(loadPaymentPort).findPendingCreatedBefore(NOW.minus(TTL), 100);
    }

    @Test @DisplayName("입금 대기형이 아닌 수단은 기한이 지나도 만료시키지 않는다(정책 재검증)")
    void skipsNonDepositMethod() {
        PaymentDomain card = pending(2L, "CARD", NOW.minusDays(30));
        when(loadPaymentPort.findPendingCreatedBefore(any(), anyInt())).thenReturn(List.of(card));

        ExpiryReport report = service.expireDue(NOW, false);

        verify(processor, never()).expireAndCancelOrder(any());
        assertThat(report.scanned()).isEqualTo(1);
        assertThat(report.expired()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
    }

    @Test @DisplayName("기한 정각은 아직 만료가 아니다 — 경계에서 건너뛴다")
    void skipsExactlyAtDeadline() {
        PaymentDomain atDeadline = pending(3L, "VIRTUAL_ACCOUNT", NOW.minus(TTL));
        when(loadPaymentPort.findPendingCreatedBefore(any(), anyInt())).thenReturn(List.of(atDeadline));

        ExpiryReport report = service.expireDue(NOW, false);

        verify(processor, never()).expireAndCancelOrder(any());
        assertThat(report.skipped()).isEqualTo(1);
    }

    @Test @DisplayName("dryRun 은 아무 것도 바꾸지 않고 예상 건수만 돌려준다")
    void dryRunChangesNothing() {
        PaymentDomain due = pending(4L, "VIRTUAL_ACCOUNT", NOW.minusHours(72));
        when(loadPaymentPort.findPendingCreatedBefore(any(), anyInt())).thenReturn(List.of(due));

        ExpiryReport report = service.expireDue(NOW, true);

        verifyNoInteractions(processor);
        assertThat(report.scanned()).isEqualTo(1);
        assertThat(report.expired()).isEqualTo(1); // "만료될 것" 예고
        assertThat(report.dryRun()).isTrue();
    }

    @Test @DisplayName("한 건이 실패해도 나머지는 계속 처리하고, 실패는 카운터로 드러낸다")
    void oneFailureDoesNotStopBatch() {
        PaymentDomain bad = pending(5L, "VIRTUAL_ACCOUNT", NOW.minusHours(50));
        PaymentDomain good = pending(6L, "BANK_TRANSFER", NOW.minusHours(50));
        when(loadPaymentPort.findPendingCreatedBefore(any(), anyInt())).thenReturn(List.of(bad, good));
        when(processor.expireAndCancelOrder(bad.getId())).thenThrow(new RuntimeException("DB 잠금 타임아웃"));
        when(processor.expireAndCancelOrder(good.getId())).thenReturn(true);

        ExpiryReport report = service.expireDue(NOW, false);

        verify(processor).expireAndCancelOrder(good.getId());
        assertThat(report.scanned()).isEqualTo(2);
        assertThat(report.expired()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
    }
}
