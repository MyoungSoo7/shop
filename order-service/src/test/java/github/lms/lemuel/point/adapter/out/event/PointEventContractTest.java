package github.lms.lemuel.point.adapter.out.event;

import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointAccountStatus;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotConsumption;
import github.lms.lemuel.point.domain.PointLotOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — 포인트 이벤트 5종이 계약 스키마를 만족해야 한다.
 *
 * <p>소비자는 account-service 다. 페이로드가 계약에서 벗어나면 GL 분개가 조용히 누락되거나
 * 잘못된 계정에 적재된다 — 그 드리프트를 빌드 시점에 막는다.
 */
@ExtendWith(MockitoExtension.class)
class PointEventContractTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-18T10:15:30Z");

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Mock TraceContextCapture traceContextCapture;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    private OutboxBackedPointEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxBackedPointEventPublisher(
                saveOutboxEventPort, OutboxJson.mapper(), traceContextCapture);
    }

    private OutboxEvent saved() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue();
    }

    private static PointAccount account() {
        return PointAccount.rehydrate(7L, 42L, new BigDecimal("10000"), BigDecimal.ZERO,
                new BigDecimal("10000"), PointAccountStatus.ACTIVE, 0L, NOW, NOW);
    }

    private static PointLot lot(PointLotOrigin origin, String amount, OffsetDateTime expiresAt) {
        PointLot lot = PointLot.issue(7L, origin, new BigDecimal(amount), NOW, expiresAt,
                "CHARGE", "chg-20260818-0001");
        lot.assignId(55L);
        return lot;
    }

    private static PointEntry entry(String amount, String refType, String refId) {
        PointEntry entry = PointEntry.use(7L, new BigDecimal(amount), refType, refId, 0,
                List.of(new PointLotConsumption(55L, new BigDecimal(amount))), "user:42");
        entry.assignId(900L);
        return entry;
    }

    @Test
    @DisplayName("현금 충전 원금 페이로드는 계약을 만족한다")
    void charged_satisfiesContract() {
        publisher.pointCharged(account(), lot(PointLotOrigin.CHARGE_PRINCIPAL, "100000", null),
                "chg-20260818-0001");

        OutboxEvent event = saved();
        assertThat(event.getEventType()).isEqualTo("PointCharged");
        EventContractValidator.assertValid("lemuel.point.charged", event.getPayload());
    }

    @Test
    @DisplayName("충전 보너스 페이로드는 계약을 만족한다 — 만료일이 있는 로트")
    void granted_satisfiesContract() {
        publisher.pointGranted(account(), lot(PointLotOrigin.CHARGE_BONUS, "8000", NOW.plusDays(365)));

        OutboxEvent event = saved();
        assertThat(event.getEventType()).isEqualTo("PointGranted");
        EventContractValidator.assertValid("lemuel.point.granted", event.getPayload());
    }

    @Test
    @DisplayName("무기한 로트(만료일 null)도 계약을 만족한다")
    void granted_withoutExpiry_satisfiesContract() {
        publisher.pointGranted(account(), lot(PointLotOrigin.MANUAL_GRANT, "5000", null));

        EventContractValidator.assertValid("lemuel.point.granted", saved().getPayload());
    }

    @Test
    @DisplayName("사용 페이로드는 계약을 만족하고 로트 배분 상세를 담는다")
    void used_satisfiesContract() {
        publisher.pointUsed(account(), entry("5000", "PAYMENT_TENDER", "55"));

        OutboxEvent event = saved();
        assertThat(event.getEventType()).isEqualTo("PointUsed");
        assertThat(event.getPayload()).contains("\"lots\"");
        EventContractValidator.assertValid("lemuel.point.used", event.getPayload());
    }

    @Test
    @DisplayName("복원 페이로드는 계약을 만족한다")
    void restored_satisfiesContract() {
        publisher.pointRestored(account(), entry("2000", "PAYMENT_TENDER_REFUND", "tender-55-2000"));

        EventContractValidator.assertValid("lemuel.point.restored", saved().getPayload());
    }

    @Test
    @DisplayName("적립 회수 페이로드는 계약을 만족한다 — 소멸과 상대계정이 달라 토픽을 나눈다")
    void revoked_satisfiesContract() {
        publisher.pointRevoked(account(), entry("500", "ORDER", "1001"));

        OutboxEvent event = saved();
        assertThat(event.getEventType()).isEqualTo("PointRevoked");
        EventContractValidator.assertValid("lemuel.point.revoked", event.getPayload());
    }

    @Test
    @DisplayName("소멸 페이로드는 계약을 만족한다")
    void expired_satisfiesContract() {
        publisher.pointExpired(account(), lot(PointLotOrigin.ORDER_EARN, "1000", NOW.plusDays(30)),
                new BigDecimal("1000"));

        EventContractValidator.assertValid("lemuel.point.expired", saved().getPayload());
    }

    @Test
    @DisplayName("금액은 문자열로 실린다 — JSON number 로 실으면 소비 측이 double 로 받아 정밀도를 잃는다")
    void amountIsSerializedAsString() {
        publisher.pointUsed(account(), entry("5000", "PAYMENT_TENDER", "55"));

        assertThat(saved().getPayload()).contains("\"amount\":\"5000\"");
    }

    @Test
    @DisplayName("메시지 키가 되는 aggregateId 는 포인트 계정 식별자다 — 같은 계정의 이벤트 순서를 지킨다")
    void aggregateIdIsAccountId() {
        publisher.pointUsed(account(), entry("5000", "PAYMENT_TENDER", "55"));

        assertThat(saved().getAggregateId()).isEqualTo("7");
    }
}
