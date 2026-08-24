package github.lms.lemuel.giftcard.adapter.out.event;

import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardEntry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — 기프트카드 이벤트 4종이 계약 스키마를 만족해야 한다.
 *
 * <p>추가로 <b>코드가 페이로드에 새지 않는지</b>를 고정한다. 이벤트는 Kafka 를 지나 다른 서비스의
 * 로그까지 흘러가므로, 재산에 해당하는 값이 실리면 유출 경로가 그만큼 넓어진다.
 */
@ExtendWith(MockitoExtension.class)
class GiftCardEventContractTest {

    private static final OffsetDateTime ISSUED_AT = OffsetDateTime.parse("2026-08-01T00:00:00Z");
    private static final OffsetDateTime EXPIRES_AT = ISSUED_AT.plusDays(365);
    private static final Long USER_ID = 42L;

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Mock TraceContextCapture traceContextCapture;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    private OutboxBackedGiftCardEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxBackedGiftCardEventPublisher(
                saveOutboxEventPort, OutboxJson.mapper(), traceContextCapture);
    }

    private OutboxEvent saved() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue();
    }

    private static GiftCard registeredCard() {
        GiftCard card = GiftCard.issue("hash-secret-value", "6789", new BigDecimal("50000"),
                ISSUED_AT, EXPIRES_AT, "admin:1", "프로모션");
        card.assignId(501L);
        card.activate();
        card.registerTo(USER_ID, ISSUED_AT.plusDays(1));
        return card;
    }

    private static GiftCardEntry entry(String amount, String refType, String refId) {
        GiftCardEntry entry = GiftCardEntry.use(501L, new BigDecimal(amount), refType, refId, 0, "user:42");
        entry.assignId(900L);
        return entry;
    }

    @Test
    @DisplayName("등록 페이로드는 계약을 만족한다")
    void registered_satisfiesContract() {
        GiftCard card = registeredCard();
        GiftCardEntry entry = GiftCardEntry.register(501L, card.getFaceAmount(),
                "REGISTRATION", "42", 0, "user:42", null);
        entry.assignId(900L);

        publisher.giftCardRegistered(card, entry);

        OutboxEvent event = saved();
        assertThat(event.getEventType()).isEqualTo("GiftCardRegistered");
        EventContractValidator.assertValid("lemuel.giftcard.registered", event.getPayload());
    }

    @Test
    @DisplayName("사용 페이로드는 계약을 만족한다")
    void used_satisfiesContract() {
        GiftCard card = registeredCard();
        card.use(new BigDecimal("30000"));

        publisher.giftCardUsed(card, entry("30000", "PAYMENT_TENDER", "77"));

        OutboxEvent event = saved();
        assertThat(event.getEventType()).isEqualTo("GiftCardUsed");
        EventContractValidator.assertValid("lemuel.giftcard.used", event.getPayload());
    }

    @Test
    @DisplayName("복원 페이로드는 계약을 만족한다")
    void restored_satisfiesContract() {
        publisher.giftCardRestored(registeredCard(),
                entry("30000", "PAYMENT_TENDER_REFUND", "tender-77-30000"));

        EventContractValidator.assertValid("lemuel.giftcard.restored", saved().getPayload());
    }

    @Test
    @DisplayName("소멸 페이로드는 계약을 만족한다")
    void expired_satisfiesContract() {
        GiftCard card = registeredCard();
        BigDecimal forfeited = card.expire(EXPIRES_AT.plusDays(1));

        publisher.giftCardExpired(card, forfeited);

        OutboxEvent event = saved();
        assertThat(event.getEventType()).isEqualTo("GiftCardExpired");
        EventContractValidator.assertValid("lemuel.giftcard.expired", event.getPayload());
    }

    @Test
    @DisplayName("코드도 코드 해시도 페이로드에 실리지 않는다 — 뒤 4자리만 나간다")
    void payloadNeverCarriesCode() {
        GiftCard card = registeredCard();
        card.use(new BigDecimal("10000"));

        publisher.giftCardUsed(card, entry("10000", "PAYMENT_TENDER", "77"));

        String payload = saved().getPayload();
        assertThat(payload).doesNotContain("hash-secret-value");
        assertThat(payload).doesNotContain("codeHash");
        assertThat(payload).contains("\"codeLast4\":\"6789\"");
    }

    @Test
    @DisplayName("금액은 문자열로 실린다 — JSON number 는 소비 측에서 정밀도를 잃는다")
    void amountIsSerializedAsString() {
        GiftCard card = registeredCard();
        card.use(new BigDecimal("30000"));

        publisher.giftCardUsed(card, entry("30000", "PAYMENT_TENDER", "77"));

        assertThat(saved().getPayload()).contains("\"amount\":\"30000\"");
    }

    @Test
    @DisplayName("메시지 키가 되는 aggregateId 는 카드 식별자다 — 같은 카드의 이벤트 순서를 지킨다")
    void aggregateIdIsGiftCardId() {
        publisher.giftCardUsed(registeredCard(), entry("10000", "PAYMENT_TENDER", "77"));

        assertThat(saved().getAggregateId()).isEqualTo("501");
    }
}
