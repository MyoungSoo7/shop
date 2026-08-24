package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 포인트 적립 로트 — 적립 1건이 곧 로트 1개다.
 *
 * <p>로트는 포인트가 예치금과 갈라지는 지점이다. 예치금은 잔고가 단일 풀이지만 포인트는
 * 건별로 <b>유효기간</b>과 <b>출처</b>가 다르다. 현금 충전분과 8% 보너스분은 만료 순서도,
 * 환불 회수 순서도, GL 상대계정({@link PointLotOrigin#isPromotional()})도 다르므로 합칠 수 없다.
 *
 * <p>상태 전이: {@code ACTIVE → EXHAUSTED}(소진) / {@code → EXPIRED}(소멸) / {@code → REVOKED}(취소).
 * {@code EXHAUSTED} 만 환불 복원으로 {@code ACTIVE} 로 되살아난다 — 아래 {@link #restoreConsumed} 참조.
 */
public class PointLot {

    private Long id;
    private final Long accountId;
    private final PointLotOrigin origin;
    private final BigDecimal originalAmount;
    private BigDecimal remainingAmount;
    private PointLotStatus status;
    private final OffsetDateTime grantedAt;
    private final OffsetDateTime expiresAt;
    private final String referenceType;
    private final String referenceId;
    private long version;

    private PointLot(Long id, Long accountId, PointLotOrigin origin, BigDecimal originalAmount,
                     BigDecimal remainingAmount, PointLotStatus status, OffsetDateTime grantedAt,
                     OffsetDateTime expiresAt, String referenceType, String referenceId, long version) {
        this.id = id;
        this.accountId = accountId;
        this.origin = origin;
        this.originalAmount = originalAmount;
        this.remainingAmount = remainingAmount;
        this.status = status;
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.version = version;
    }

    /**
     * 로트 발급. {@code expiresAt} 이 null 이면 무기한(수기 지급 등)이다.
     *
     * <p>{@code (accountId, origin, referenceType, referenceId)} 조합이 DB UNIQUE 라,
     * 같은 근거로 두 번 발급하려는 시도는 L3 멱등 방어선에서 막힌다.
     */
    public static PointLot issue(Long accountId, PointLotOrigin origin, BigDecimal amount,
                                 OffsetDateTime grantedAt, OffsetDateTime expiresAt,
                                 String referenceType, String referenceId) {
        BigDecimal value = PointAmounts.requirePoint(amount, "issue");
        if (accountId == null || origin == null || grantedAt == null
                || referenceType == null || referenceId == null) {
            throw new InvalidPointStateException(
                    "로트 발급에 필요한 식별 정보가 비었습니다", "NONE", "issue");
        }
        if (expiresAt != null && !expiresAt.isAfter(grantedAt)) {
            throw new InvalidPointStateException(
                    "만료일(" + expiresAt + ")은 적립일(" + grantedAt + ")보다 뒤여야 합니다", "NONE", "issue");
        }
        return new PointLot(null, accountId, origin, value, value, PointLotStatus.ACTIVE,
                grantedAt, expiresAt, referenceType, referenceId, 0L);
    }

    public static PointLot rehydrate(Long id, Long accountId, PointLotOrigin origin,
                                     BigDecimal originalAmount, BigDecimal remainingAmount,
                                     PointLotStatus status, OffsetDateTime grantedAt,
                                     OffsetDateTime expiresAt, String referenceType,
                                     String referenceId, long version) {
        return new PointLot(id, accountId, origin,
                PointAmounts.normalize(originalAmount, "rehydrate"),
                PointAmounts.normalize(remainingAmount, "rehydrate"),
                status, grantedAt, expiresAt, referenceType, referenceId, version);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 소비·복원
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 이 로트에서 정확히 {@code amount} 만큼 소비한다. 잔량을 넘는 요청은 호출자
     * ({@link PointLotSelector})가 이미 걸러야 하지만, 도메인이 다시 막는다.
     */
    public void consume(BigDecimal amount) {
        BigDecimal value = PointAmounts.requirePoint(amount, "consume");
        requireActive("consume");
        if (remainingAmount.compareTo(value) < 0) {
            throw new InsufficientPointException(
                    "로트 잔량 부족: 요청 " + value + ", 잔량 " + remainingAmount, value, remainingAmount);
        }
        this.remainingAmount = this.remainingAmount.subtract(value);
        if (remainingAmount.signum() == 0) {
            this.status = PointLotStatus.EXHAUSTED;
        }
    }

    /**
     * 환불로 소비분을 되돌린다.
     *
     * <p>소진({@code EXHAUSTED})된 로트도 되살린다. 고객이 쓰지 않았더라면 이 로트는 여전히
     * 원래 만료일을 갖고 있었을 것이므로, 원 로트로 되돌리는 편이 정확하다 — 새 로트를 발급하면
     * 유효기간을 덤으로 얹어 주는 셈이 된다. 반대로 이미 <b>소멸</b>했거나 <b>취소</b>된 로트는
     * 되살리지 않는다(원장 역분개 원칙과 같은 이유). 그 경우 응용 서비스가
     * {@link PointLotOrigin#REFUND_RESTORE} 로 새 로트를 발급한다.
     */
    public void restoreConsumed(BigDecimal amount) {
        BigDecimal value = PointAmounts.requirePoint(amount, "restoreConsumed");
        if (status != PointLotStatus.ACTIVE && status != PointLotStatus.EXHAUSTED) {
            throw new InvalidPointStateException(
                    "소멸·취소된 로트에는 복원할 수 없습니다: " + status, status.name(), "restoreConsumed");
        }
        BigDecimal restored = remainingAmount.add(value);
        if (restored.compareTo(originalAmount) > 0) {
            throw new InvalidPointStateException(
                    "원 발급액을 넘겨 복원할 수 없습니다: 복원 후 " + restored + " > 원금 " + originalAmount,
                    status.name(), "restoreConsumed");
        }
        this.remainingAmount = restored;
        this.status = PointLotStatus.ACTIVE;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 종단 전이
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 유효기간 소멸. 남은 잔량을 반환하며, 호출자는 그만큼 계정 잔고를 차감해야 한다.
     * 만료 시각 전에는 소멸시킬 수 없다 — 배치 버그를 도메인이 막는다.
     */
    public BigDecimal expire(OffsetDateTime at) {
        requireActive("expire");
        if (!isExpiredAt(at)) {
            throw new InvalidPointStateException(
                    "아직 만료되지 않은 로트입니다: expiresAt=" + expiresAt + ", now=" + at,
                    status.name(), "expire");
        }
        BigDecimal forfeited = remainingAmount;
        this.remainingAmount = PointAmounts.zero();
        this.status = PointLotStatus.EXPIRED;
        return forfeited;
    }

    /** 적립 취소(주문 취소 등). 남은 잔량을 반환한다. */
    public BigDecimal revoke() {
        requireActive("revoke");
        BigDecimal revoked = remainingAmount;
        this.remainingAmount = PointAmounts.zero();
        this.status = PointLotStatus.REVOKED;
        return revoked;
    }

    /** 만료 판정 — 만료 시각 <b>정각은 아직 유효</b>하다(반열림 경계). 무기한 로트는 언제나 false. */
    public boolean isExpiredAt(OffsetDateTime at) {
        return expiresAt != null && at.isAfter(expiresAt);
    }

    public boolean isConsumable() {
        return status.isConsumable() && remainingAmount.signum() > 0;
    }

    public void assignId(Long id) {
        if (this.id != null) {
            throw new InvalidPointStateException("이미 ID 가 할당된 로트입니다: " + this.id,
                    status.name(), "assignId");
        }
        this.id = id;
    }

    public void syncVersion(long version) {
        this.version = version;
    }

    private void requireActive(String operation) {
        if (status != PointLotStatus.ACTIVE) {
            throw new InvalidPointStateException(
                    "활성 로트가 아닙니다: " + status, status.name(), operation);
        }
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public PointLotOrigin getOrigin() { return origin; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public BigDecimal getRemainingAmount() { return remainingAmount; }
    public PointLotStatus getStatus() { return status; }
    public OffsetDateTime getGrantedAt() { return grantedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public long getVersion() { return version; }
}
