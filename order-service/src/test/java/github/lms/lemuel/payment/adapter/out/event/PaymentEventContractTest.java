package github.lms.lemuel.payment.adapter.out.event;

import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.payment.application.port.out.SellerSettlementMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — 이 어댑터가 실제로 조립하는 outbox 페이로드가
 * shared-common 의 이벤트 계약 스키마를 통과해야 한다. 필수 필드 삭제·이름변경·타입변경
 * (예: amount 를 문자열→숫자로) 은 여기서 빌드 실패로 드러난다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Mock TraceContextCapture traceContextCapture;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    OutboxBackedEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxBackedEventPublisher(
                saveOutboxEventPort, OutboxJson.mapper(), traceContextCapture);
    }

    private String savedPayload() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue().getPayload();
    }

    @Test
    @DisplayName("PaymentCaptured(전체 필드) 페이로드는 lemuel.payment.captured 계약을 만족한다")
    void paymentCaptured_fullPayload_satisfiesContract() {
        publisher.publishPaymentCaptured(1001L, 5001L, new BigDecimal("45000"),
                LocalDateTime.of(2026, 7, 1, 10, 15, 30),
                "CARD", "toss-tx-20260701-0001",
                new SellerSettlementMeta(777L, "VIP", "T_PLUS_3"));

        EventContractValidator.assertValid("lemuel.payment.captured", savedPayload());
    }

    @Test
    @DisplayName("PaymentCaptured(optional 전부 생략 — 셀러 미할당) 페이로드도 계약을 만족한다")
    void paymentCaptured_minimalPayload_satisfiesContract() {
        publisher.publishPaymentCaptured(1001L, 5001L, new BigDecimal("45000"),
                null, null, null, null);

        EventContractValidator.assertValid("lemuel.payment.captured", savedPayload());
    }

    @Test
    @DisplayName("PaymentRefunded(누적+건별+refundId) 페이로드는 lemuel.payment.refunded 계약을 만족한다")
    void paymentRefunded_fullPayload_satisfiesContract() {
        publisher.publishPaymentRefunded(1001L, 5001L,
                new BigDecimal("15000"), new BigDecimal("5000"), 42L);

        EventContractValidator.assertValid("lemuel.payment.refunded", savedPayload());
    }

    @Test
    @DisplayName("PaymentRefunded(레거시 — 누적만) 페이로드도 계약을 만족한다")
    void paymentRefunded_legacyCumulativeOnly_satisfiesContract() {
        publisher.publishPaymentRefunded(1001L, 5001L,
                new BigDecimal("15000"), null, null);

        EventContractValidator.assertValid("lemuel.payment.refunded", savedPayload());
    }
}
