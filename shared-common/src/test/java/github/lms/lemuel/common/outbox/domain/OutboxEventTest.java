package github.lms.lemuel.common.outbox.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OutboxEventTest {

    @Test
    @DisplayName("pending 팩토리는 status=PENDING, retryCount=0, publishedAt=null, 새 eventId 를 생성한다")
    void pendingFactoryDefaults() {
        OutboxEvent e = OutboxEvent.pending("Payment", "42", "PaymentCaptured", "{\"paymentId\":42}");

        assertThat(e.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(e.getRetryCount()).isZero();
        assertThat(e.getPublishedAt()).isNull();
        assertThat(e.getLastError()).isNull();
        assertThat(e.getEventId()).isNotNull();
        assertThat(e.getCreatedAt()).isNotNull();
        assertThat(e.isPending()).isTrue();
    }

    @Test
    @DisplayName("markPublished 는 status 를 PUBLISHED 로, publishedAt 을 현재로 세팅하고 lastError 를 지운다")
    void markPublished() {
        OutboxEvent e = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        e.markFailed("transient");

        e.markPublished();

        assertThat(e.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(e.getPublishedAt()).isNotNull();
        assertThat(e.getLastError()).isNull();
    }

    @Test
    @DisplayName("markFailed 는 retryCount 를 증가시키고 lastError 를 기록한다. 10회 초과 시 FAILED 전이")
    void markFailedTransitionsToFailedAfterThreshold() {
        OutboxEvent e = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");

        for (int i = 0; i < 9; i++) {
            e.markFailed("boom " + i);
        }
        assertThat(e.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(e.getRetryCount()).isEqualTo(9);

        e.markFailed("final");
        assertThat(e.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(e.getRetryCount()).isEqualTo(10);
        assertThat(e.getLastError()).isEqualTo("final");
    }

    @Test
    @DisplayName("같은 페이로드로 두 번 생성한 이벤트는 서로 다른 eventId 를 가진다 (UUID 충돌 없음)")
    void uniqueEventIds() {
        OutboxEvent a = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        OutboxEvent b = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");

        assertThat(a.getEventId()).isNotEqualTo(b.getEventId());
    }

    @Test
    @DisplayName("requeue: FAILED → PENDING, retryCount=0 으로 초기화. 운영자 재처리 진입점.")
    void requeueResetsState() {
        OutboxEvent e = pushToFailed();

        e.requeue();

        assertThat(e.isPending()).isTrue();
        assertThat(e.getRetryCount()).isZero();
        // lastError 는 보존 — 사후 추적 가능
        assertThat(e.getLastError()).isEqualTo("final");
    }

    @Test
    @DisplayName("requeue: FAILED 가 아닌 상태에서 호출 시 IllegalStateException")
    void requeueRejectsWrongState() {
        OutboxEvent e = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, e::requeue);
    }

    @Test
    @DisplayName("skip: FAILED → PUBLISHED, lastError 에 [SKIPPED] prefix + 사유 기록")
    void skipMarksPublishedWithReason() {
        OutboxEvent e = pushToFailed();

        e.skip("이미 다른 경로로 보정 완료");

        assertThat(e.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(e.getPublishedAt()).isNotNull();
        assertThat(e.getLastError()).startsWith("[SKIPPED]");
        assertThat(e.getLastError()).contains("이미 다른 경로로 보정 완료");
    }

    @Test
    @DisplayName("skip: 사유 없이 호출 시 IllegalArgumentException (감사 추적용 필수)")
    void skipRequiresReason() {
        OutboxEvent e = pushToFailed();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> e.skip(null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> e.skip(" "));
    }

    @Test
    @DisplayName("skip: PENDING 상태에서 호출 시 IllegalStateException — FAILED 만 가능")
    void skipRejectsNonFailedState() {
        OutboxEvent e = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> e.skip("이유"));
    }

    @Test
    @DisplayName("N4: pending 은 occurredAt 을 UTC 로 찍고 eventVersion=1, producer 는 어댑터 몫으로 비운다")
    void pendingFillsEnvelope() {
        OutboxEvent e = OutboxEvent.pending("Payment", "42", "PaymentCaptured", "{}");

        assertThat(e.getOccurredAt()).isNotNull();
        assertThat(e.getOccurredAt().getOffset()).isEqualTo(java.time.ZoneOffset.UTC);
        assertThat(e.getOccurredAt().toInstant())
                .isCloseTo(java.time.Instant.now(), within(10, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(e.getEventVersion()).isEqualTo(OutboxEvent.DEFAULT_EVENT_VERSION);
        assertThat(e.getProducer()).isNull();
    }

    @Test
    @DisplayName("N4: 페이로드 스키마를 올릴 때 eventVersion 을 지정할 수 있다")
    void pendingAcceptsExplicitVersion() {
        OutboxEvent e = OutboxEvent.pending("Payment", "42", "PaymentCaptured", "{}", null, 2);

        assertThat(e.getEventVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("N4: withProducer 는 봉투의 발행자만 채우고 나머지 식별자는 보존한다")
    void withProducerPreservesIdentity() {
        OutboxEvent e = OutboxEvent.pending("Payment", "42", "PaymentCaptured", "{}");

        OutboxEvent stamped = e.withProducer("lemuel-settlement");

        assertThat(stamped.getProducer()).isEqualTo("lemuel-settlement");
        assertThat(stamped.getEventId()).isEqualTo(e.getEventId());
        assertThat(stamped.getOccurredAt()).isEqualTo(e.getOccurredAt());
        assertThat(stamped.getPayload()).isEqualTo(e.getPayload());
    }

    @Test
    @DisplayName("N4: 봉투 없는 레거시 행 rehydrate 는 createdAt 을 UTC 로 간주해 occurredAt 을 채운다")
    void legacyRehydrateFallsBackToCreatedAt() {
        java.time.LocalDateTime createdAt = java.time.LocalDateTime.of(2026, 7, 1, 12, 0);

        OutboxEvent e = OutboxEvent.rehydrate(1L, "Payment", "42", "PaymentCaptured",
                java.util.UUID.randomUUID(), "{}", OutboxEventStatus.PENDING, 0, null,
                createdAt, null, null);

        assertThat(e.getOccurredAt()).isEqualTo(createdAt.atOffset(java.time.ZoneOffset.UTC));
        assertThat(e.getEventVersion()).isEqualTo(OutboxEvent.DEFAULT_EVENT_VERSION);
    }

    private static OutboxEvent pushToFailed() {
        OutboxEvent e = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        for (int i = 0; i < 9; i++) {
            e.markFailed("transient " + i);
        }
        e.markFailed("final");
        return e;
    }
}
