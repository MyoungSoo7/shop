package github.lms.lemuel.marketing.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 보상 요청 애그리거트 — 이 서비스가 포인트에 대해 아는 전부.
 *
 * <p>상태 전이 세 개가 전부다. 그중 둘은 <b>같은 일이 두 번 와도 조용해야</b> 한다 —
 * {@code lemuel.point.granted} 는 at-least-once 라서 재수신이 정상 동작이고, 재수신을 예외로
 * 만들면 컨슈머가 재시도하다 DLQ 로 간다.
 */
class RewardGrantTest {

    private static final UUID REFERENCE = UUID.randomUUID();
    private static final UUID CAMPAIGN = UUID.randomUUID();
    private static final LocalDate EXPIRES_ON = LocalDate.of(2026, 12, 31);

    private static RewardGrant requestNow(BigDecimal amount) {
        return RewardGrant.requestNow(UUID.randomUUID(), RewardSource.ATTENDANCE_DAILY, REFERENCE, CAMPAIGN,
                "member-1", amount, EXPIRES_ON, "8월 출석 [일일 출석]");
    }

    private static RewardGrant scheduled(LocalDate scheduledOn) {
        return RewardGrant.scheduled(UUID.randomUUID(), RewardSource.LUCKYBOX, REFERENCE, CAMPAIGN,
                "member-1", BigDecimal.TEN, EXPIRES_ON, "8월 럭키박스 [당첨]", scheduledOn);
    }

    // ------------------------------------------------------------ 생성 규칙

    @Test
    void 즉시_요청은_REQUESTED_로_만들어진다() {
        RewardGrant grant = requestNow(new BigDecimal("100"));

        assertEquals(RewardStatus.REQUESTED, grant.status());
        assertNotNull(grant.requestedAt());
        assertNull(grant.scheduledOn());
        assertNull(grant.confirmedAt());
        assertNull(grant.failureReason());
        assertEquals(RewardSource.ATTENDANCE_DAILY, grant.source());
        assertEquals(REFERENCE, grant.referenceId());
        assertEquals(CAMPAIGN, grant.campaignId());
        assertEquals("member-1", grant.memberRef());
        assertEquals(EXPIRES_ON, grant.expiresOn());
        assertEquals("8월 출석 [일일 출석]", grant.memo());
        assertEquals(0L, grant.version());
    }

    @Test
    void 예약_요청은_PENDING_으로_만들어지고_아직_요청_시각이_없다() {
        LocalDate on = LocalDate.of(2026, 9, 1);
        RewardGrant grant = scheduled(on);

        assertEquals(RewardStatus.PENDING, grant.status());
        assertEquals(on, grant.scheduledOn());
        assertNull(grant.requestedAt());
    }

    @Test
    void 예약_요청은_지급일이_필수다() {
        assertThrows(IllegalArgumentException.class, () -> scheduled(null));
    }

    @Test
    void 식별자와_회원이_없으면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> RewardGrant.requestNow(
                null, RewardSource.LUCKYBOX, REFERENCE, CAMPAIGN, "m", BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> RewardGrant.requestNow(
                UUID.randomUUID(), null, REFERENCE, CAMPAIGN, "m", BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> RewardGrant.requestNow(
                UUID.randomUUID(), RewardSource.LUCKYBOX, null, CAMPAIGN, "m", BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> RewardGrant.requestNow(
                UUID.randomUUID(), RewardSource.LUCKYBOX, REFERENCE, null, "m", BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> RewardGrant.requestNow(
                UUID.randomUUID(), RewardSource.LUCKYBOX, REFERENCE, CAMPAIGN, " ", BigDecimal.ONE, null, null));
    }

    /** 0원 보상은 원장에 흔적만 남기고 사용자에겐 아무 일도 아니다 — 만들지 않는다. */
    @Test
    void 금액이_0_이하이거나_없으면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> requestNow(null));
        assertThrows(IllegalArgumentException.class, () -> requestNow(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> requestNow(new BigDecimal("-1")));
    }

    // ------------------------------------------------------------ 상태 전이

    @Test
    void 대기중인_보상만_요청으로_넘길_수_있다() {
        RewardGrant grant = scheduled(LocalDate.of(2026, 9, 1));

        grant.markRequested();

        assertEquals(RewardStatus.REQUESTED, grant.status());
        assertNotNull(grant.requestedAt());
        // 두 번째는 상태가 이미 REQUESTED 라 거절 — 같은 보상이 두 번 발행되는 걸 막는다.
        assertThrows(IllegalStateException.class, grant::markRequested);
    }

    @Test
    void 적립_확인은_CONFIRMED_로_넘기고_실패_사유를_지운다() {
        RewardGrant grant = requestNow(BigDecimal.TEN);
        grant.markFailed("잔액 계좌 없음");

        grant.markConfirmed();

        assertEquals(RewardStatus.CONFIRMED, grant.status());
        assertNotNull(grant.confirmedAt());
        assertNull(grant.failureReason());
    }

    @Test
    void 확인이_두_번_와도_조용히_통과한다() {
        RewardGrant grant = requestNow(BigDecimal.TEN);
        grant.markConfirmed();
        Instant first = grant.confirmedAt();

        grant.markConfirmed();

        assertEquals(RewardStatus.CONFIRMED, grant.status());
        assertEquals(first, grant.confirmedAt());   // 시각도 덮어쓰지 않는다
    }

    @Test
    void 이미_적립된_보상은_실패로_되돌리지_않는다() {
        RewardGrant grant = requestNow(BigDecimal.TEN);
        grant.markConfirmed();

        grant.markFailed("뒤늦은 거절 통지");

        assertEquals(RewardStatus.CONFIRMED, grant.status());
        assertNull(grant.failureReason());
    }

    @Test
    void 실패는_사유를_남긴다() {
        RewardGrant grant = requestNow(BigDecimal.TEN);

        grant.markFailed("원장 거절");

        assertEquals(RewardStatus.FAILED, grant.status());
        assertEquals("원장 거절", grant.failureReason());
    }

    // ------------------------------------------------------------ 지급 대상 판정

    @Test
    void 지급일이_지났고_대기중이면_지급_대상이다() {
        LocalDate on = LocalDate.of(2026, 9, 1);
        RewardGrant grant = scheduled(on);

        assertFalse(grant.isDue(on.minusDays(1)));
        assertTrue(grant.isDue(on));
        assertTrue(grant.isDue(on.plusDays(1)));
    }

    @Test
    void 대기중이_아니거나_예정일이_없으면_지급_대상이_아니다() {
        assertFalse(requestNow(BigDecimal.TEN).isDue(LocalDate.of(2026, 9, 1)));

        RewardGrant requested = scheduled(LocalDate.of(2026, 9, 1));
        requested.markRequested();
        assertFalse(requested.isDue(LocalDate.of(2026, 9, 1)));
    }

    @Test
    void 되살린_보상은_영속_상태를_그대로_들고_온다() {
        Instant requestedAt = Instant.parse("2026-08-27T00:00:00Z");
        Instant confirmedAt = Instant.parse("2026-08-27T00:00:05Z");
        UUID id = UUID.randomUUID();

        RewardGrant grant = RewardGrant.rehydrate(id, RewardSource.ATTENDANCE_GOAL, REFERENCE, CAMPAIGN,
                "member-9", new BigDecimal("500"), EXPIRES_ON, "메모", RewardStatus.CONFIRMED,
                LocalDate.of(2026, 9, 1), requestedAt, confirmedAt, null, 7L);

        assertEquals(id, grant.id());
        assertEquals(RewardSource.ATTENDANCE_GOAL, grant.source());
        assertEquals(new BigDecimal("500"), grant.amount());
        assertEquals(requestedAt, grant.requestedAt());
        assertEquals(confirmedAt, grant.confirmedAt());
        assertEquals(7L, grant.version());
    }
}
