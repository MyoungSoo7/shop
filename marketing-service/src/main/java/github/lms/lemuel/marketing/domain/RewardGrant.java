package github.lms.lemuel.marketing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 보상 요청 애그리거트 — 이 서비스가 포인트에 대해 아는 전부다.
 *
 * <p>레거시는 이벤트 서비스가 {@code mileageService.insertMileage(...)} 를 직접 불러 회원의
 * 마일리지 잔액을 올렸다. 한 프로세스 안이라 그게 가능했지만, 그 결과 "포인트 잔액이 왜 이렇게
 * 됐는지" 를 알려면 주문·이벤트·관리자 도구를 전부 뒤져야 했다.
 *
 * <p>여기서는 잔액을 갖지 않는다. 원장의 주인은 order-service 이고, 마케팅은
 * {@code lemuel.marketing.reward_requested} 로 <b>요청만</b> 낸다. 잔액을 두 곳에 두면
 * 그건 경계를 나눈 게 아니라 정합성을 나눈 것이다.
 *
 * <p>{@code id} 는 그대로 원장 적립의 멱등 키({@code referenceId})가 된다 — 이벤트가 몇 번
 * 재발행돼도 적립은 한 번이다.
 */
public final class RewardGrant {

    private final UUID id;
    private final RewardSource source;
    private final UUID referenceId;
    private final UUID campaignId;
    private final String memberRef;
    private final BigDecimal amount;
    private final LocalDate expiresOn;
    private final String memo;
    private RewardStatus status;
    private final LocalDate scheduledOn;
    private Instant requestedAt;
    private Instant confirmedAt;
    private String failureReason;
    private final long version;

    private RewardGrant(UUID id, RewardSource source, UUID referenceId, UUID campaignId, String memberRef,
                        BigDecimal amount, LocalDate expiresOn, String memo, RewardStatus status,
                        LocalDate scheduledOn, Instant requestedAt, Instant confirmedAt, String failureReason,
                        long version) {
        if (id == null || source == null || referenceId == null || campaignId == null) {
            throw new IllegalArgumentException("id/source/referenceId/campaignId is required");
        }
        if (memberRef == null || memberRef.isBlank()) throw new IllegalArgumentException("memberRef is required");
        if (amount == null || amount.signum() <= 0) {
            // 0원 보상은 원장에 흔적만 남기고 사용자에겐 아무 일도 아니다. 만들지 않는다.
            throw new IllegalArgumentException("보상 금액은 0보다 커야 한다");
        }
        this.id = id;
        this.source = source;
        this.referenceId = referenceId;
        this.campaignId = campaignId;
        this.memberRef = memberRef;
        this.amount = amount;
        this.expiresOn = expiresOn;
        this.memo = memo;
        this.status = status;
        this.scheduledOn = scheduledOn;
        this.requestedAt = requestedAt;
        this.confirmedAt = confirmedAt;
        this.failureReason = failureReason;
        this.version = version;
    }

    /**
     * 즉시 요청할 보상. 만들자마자 REQUESTED 이고, 같은 트랜잭션에서 outbox 에 실린다.
     */
    public static RewardGrant requestNow(UUID id, RewardSource source, UUID referenceId, UUID campaignId,
                                         String memberRef, BigDecimal amount, LocalDate expiresOn, String memo) {
        return new RewardGrant(id, source, referenceId, campaignId, memberRef, amount, expiresOn, memo,
                RewardStatus.REQUESTED, null, Instant.now(), null, null, 0L);
    }

    /**
     * 지급일까지 대기할 보상 (일괄 지급 캠페인).
     *
     * <p>당첨 사실은 지금 확정되고 요청만 미뤄진다. 나중에 만들면 그 사이에 캠페인이 수정되거나
     * 경품이 소진돼서 "당첨됐는데 줄 게 없는" 상태가 생긴다.
     */
    public static RewardGrant scheduled(UUID id, RewardSource source, UUID referenceId, UUID campaignId,
                                        String memberRef, BigDecimal amount, LocalDate expiresOn, String memo,
                                        LocalDate scheduledOn) {
        if (scheduledOn == null) throw new IllegalArgumentException("scheduledOn is required");
        return new RewardGrant(id, source, referenceId, campaignId, memberRef, amount, expiresOn, memo,
                RewardStatus.PENDING, scheduledOn, null, null, null, 0L);
    }

    /** 영속 상태에서 애그리거트를 되살린다 — 어댑터 전용 진입점. */
    public static RewardGrant rehydrate(UUID id, RewardSource source, UUID referenceId, UUID campaignId,
                                        String memberRef, BigDecimal amount, LocalDate expiresOn, String memo,
                                        RewardStatus status, LocalDate scheduledOn, Instant requestedAt,
                                        Instant confirmedAt, String failureReason, long version) {
        return new RewardGrant(id, source, referenceId, campaignId, memberRef, amount, expiresOn, memo, status,
                scheduledOn, requestedAt, confirmedAt, failureReason, version);
    }

    /** 대기 중이던 보상을 요청 상태로 넘긴다 (정산 스케줄러). */
    public void markRequested() {
        if (status != RewardStatus.PENDING) {
            throw new IllegalStateException("대기 중인 보상만 요청할 수 있다: id=" + id + ", status=" + status);
        }
        this.status = RewardStatus.REQUESTED;
        this.requestedAt = Instant.now();
    }

    /**
     * 원장 적립을 확인했다.
     *
     * <p>이미 CONFIRMED 면 조용히 통과한다 — {@code lemuel.point.granted} 는 at-least-once 라서
     * 같은 이벤트가 두 번 온다. 두 번째를 예외로 만들면 컨슈머가 재시도하다 DLQ 로 간다.
     */
    public void markConfirmed() {
        if (status == RewardStatus.CONFIRMED) {
            return;
        }
        this.status = RewardStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        if (status == RewardStatus.CONFIRMED) {
            return;   // 이미 적립된 건 실패로 되돌리지 않는다
        }
        this.status = RewardStatus.FAILED;
        this.failureReason = reason;
    }

    public boolean isDue(LocalDate on) {
        return status == RewardStatus.PENDING && scheduledOn != null && !scheduledOn.isAfter(on);
    }

    public UUID id() { return id; }
    public RewardSource source() { return source; }
    public UUID referenceId() { return referenceId; }
    public UUID campaignId() { return campaignId; }
    public String memberRef() { return memberRef; }
    public BigDecimal amount() { return amount; }
    public LocalDate expiresOn() { return expiresOn; }
    public String memo() { return memo; }
    public RewardStatus status() { return status; }
    public LocalDate scheduledOn() { return scheduledOn; }
    public Instant requestedAt() { return requestedAt; }
    public Instant confirmedAt() { return confirmedAt; }
    public String failureReason() { return failureReason; }
    public long version() { return version; }
}
