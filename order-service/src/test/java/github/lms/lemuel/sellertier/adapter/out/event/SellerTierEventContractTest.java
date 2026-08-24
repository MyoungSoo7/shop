package github.lms.lemuel.sellertier.adapter.out.event;

import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.TierChangeReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — 등급 변경 페이로드가 lemuel.seller.tier_changed 계약을 만족해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class SellerTierEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Mock TraceContextCapture traceContextCapture;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    OutboxBackedSellerTierEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxBackedSellerTierEventPublisher(
                saveOutboxEventPort, OutboxJson.mapper(), traceContextCapture);
    }

    private String savedPayload() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue().getPayload();
    }

    @Test @DisplayName("자동 승급 페이로드는 계약을 만족한다")
    void autoPromotion_satisfiesContract() {
        publisher.publishTierChanged(777L, SellerTierGrade.NORMAL, SellerTierGrade.VIP,
                TierChangeReason.AUTO_PROMOTION, LocalDate.of(2026, 9, 1), new BigDecimal("612500000"));

        EventContractValidator.assertValid("lemuel.seller.tier_changed", savedPayload());
    }

    @Test @DisplayName("최초 부여(prevTier=null)도 계약을 만족한다")
    void initialAssignment_satisfiesContract() {
        publisher.publishTierChanged(777L, null, SellerTierGrade.NORMAL,
                TierChangeReason.AUTO_PROMOTION, LocalDate.of(2026, 9, 1), BigDecimal.ZERO);

        EventContractValidator.assertValid("lemuel.seller.tier_changed", savedPayload());
    }

    @Test @DisplayName("관리자 지정(근거 금액 없음)도 계약을 만족한다")
    void adminOverride_satisfiesContract() {
        publisher.publishTierChanged(777L, SellerTierGrade.NORMAL, SellerTierGrade.STRATEGIC,
                TierChangeReason.ADMIN_OVERRIDE, LocalDate.of(2026, 9, 1), null);

        EventContractValidator.assertValid("lemuel.seller.tier_changed", savedPayload());
    }

    @Test @DisplayName("근거 금액은 문자열로 실어 정밀도를 지킨다 — 숫자로 실으면 소비측에서 깎인다")
    void basisAmountIsPlainString() {
        publisher.publishTierChanged(777L, SellerTierGrade.NORMAL, SellerTierGrade.VIP,
                TierChangeReason.AUTO_PROMOTION, LocalDate.of(2026, 9, 1),
                new BigDecimal("612500000.55"));

        assertThat(savedPayload()).contains("612500000.55");
    }

    @Test @DisplayName("백필 재발행(BACKFILL)도 계약을 만족한다")
    void backfill_satisfiesContract() {
        publisher.publishTierChanged(777L, null, SellerTierGrade.VIP,
                TierChangeReason.BACKFILL, LocalDate.of(2026, 3, 1), null);

        EventContractValidator.assertValid("lemuel.seller.tier_changed", savedPayload());
    }

    @Test @DisplayName("Outbox 를 경유한다 — 등급 변경과 통지가 한 커밋으로 묶인다")
    void writesThroughOutbox() {
        publisher.publishTierChanged(777L, SellerTierGrade.NORMAL, SellerTierGrade.VIP,
                TierChangeReason.AUTO_PROMOTION, LocalDate.of(2026, 9, 1), BigDecimal.TEN);

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getAggregateType()).isEqualTo("Seller");
        assertThat(outboxCaptor.getValue().getAggregateId()).isEqualTo("777");
    }
}
