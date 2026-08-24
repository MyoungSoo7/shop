package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InvalidPointStateException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 포인트 선점 — 입금 대기 결제가 붙잡아 두는 잔고 조각 (순수 도메인, 프레임워크·시계 의존 0).
 *
 * <p><b>왜 필요한가</b>: 가상계좌는 입금 전까지 결제가 확정되지 않는다. 그 사이 포인트를
 * <b>차감하지 않으면</b> 같은 포인트를 다른 주문에 또 쓸 수 있고, <b>차감해 버리면</b> 미입금
 * 취소마다 복원 경로가 필요하다. 선점은 그 사이를 메운다 — 가용에서 빼서 잠그되 총액은 그대로
 * 두고, 결말이 정해지는 순간(입금 확인 / 기한 경과 / 주문 취소)에 한 번만 움직인다.
 *
 * <p><b>로트를 건드리지 않는다.</b> 실제 소비(로트 차감·원장 엔트리)는 확정 시점에만 일어난다.
 * 선점 단계에서 로트를 미리 찍어 두면, 대기 중에 그 로트가 소멸했을 때 무엇을 어떻게 되돌릴지가
 * 다시 문제가 된다. 잠근 것은 "금액"이지 "그 로트"가 아니다.
 *
 * <p><b>원장 엔트리를 남기지 않는 이유</b>: 엔트리는 총액 변동의 기록인데 선점은 총액을 바꾸지
 * 않는다. 여기에 새 엔트리 유형을 만들면 DB CHECK·3자 대조 SQL·이벤트 계약 5종을 함께 고쳐야
 * 한다(docs/plan/point-ledger.md). 선점의 감사 흔적은 이 레코드 자신이 진다.
 */
public class PointHold {

    private Long id;
    private final Long accountId;
    private final BigDecimal amount;
    private PointHoldStatus status;
    private final String referenceType;
    private final String referenceId;
    private final OffsetDateTime createdAt;
    private OffsetDateTime resolvedAt;

    private PointHold(Long id, Long accountId, BigDecimal amount, PointHoldStatus status,
                      String referenceType, String referenceId,
                      OffsetDateTime createdAt, OffsetDateTime resolvedAt) {
        this.id = id;
        this.accountId = accountId;
        this.amount = amount;
        this.status = status;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }

    /**
     * 새 선점 — ACTIVE 로 시작한다.
     *
     * <p>참조({@code referenceType}/{@code referenceId})를 필수로 두는 이유: 없으면 이 선점이 어느
     * 결제 것인지 알 수 없고, 그러면 입금이 와도 무엇을 확정할지·기한이 지나도 무엇을 풀지 짚을 수
     * 없어 <b>잔고가 영영 잠긴다</b>. 저장소의 자연키(멱등 L3)이기도 하다.
     */
    public static PointHold place(Long accountId, BigDecimal amount,
                                  String referenceType, String referenceId, OffsetDateTime now) {
        if (accountId == null) {
            throw new InvalidPointStateException("계정 없이 포인트를 선점할 수 없습니다", "NONE", "place");
        }
        requireReference(referenceType, "referenceType");
        requireReference(referenceId, "referenceId");
        return new PointHold(null, accountId, PointAmounts.requirePoint(amount, "hold"),
                PointHoldStatus.ACTIVE, referenceType.trim(), referenceId.trim(), now, null);
    }

    /** 영속 상태로부터 복원. */
    public static PointHold rehydrate(Long id, Long accountId, BigDecimal amount, PointHoldStatus status,
                                      String referenceType, String referenceId,
                                      OffsetDateTime createdAt, OffsetDateTime resolvedAt) {
        return new PointHold(id, accountId, PointAmounts.normalize(amount, "rehydrate"), status,
                referenceType, referenceId, createdAt, resolvedAt);
    }

    /** 입금이 확인돼 실제 차감으로 확정한다. */
    public void capture(OffsetDateTime now) {
        resolveTo(PointHoldStatus.CAPTURED, now);
    }

    /** 주문 취소 등으로 명시적으로 푼다. */
    public void release(OffsetDateTime now) {
        resolveTo(PointHoldStatus.RELEASED, now);
    }

    /** 입금 기한이 지나 자동으로 풀린다. */
    public void expire(OffsetDateTime now) {
        resolveTo(PointHoldStatus.EXPIRED, now);
    }

    /**
     * 종단 전이 공통 경로.
     *
     * <p>같은 상태 재적용은 멱등 no-op 이되 <b>해소 시각은 최초를 보존</b>한다 — 재시도 경로가
     * 여럿이라(배치 재실행·수동 조작) 덮어쓰면 "언제 풀렸나"가 흔들리고, 입금 vs 만료 경합을
     * 사후에 재구성할 수 없게 된다.
     *
     * <p>종단끼리의 전이는 막는다. 이것이 경합의 최종 방어선이다 — 배치가 만료시킨 선점을 뒤늦은
     * 입금이 확정해 버리면, 이미 가용으로 돌아간 포인트를 한 번 더 쓰는 것이 된다.
     */
    private void resolveTo(PointHoldStatus target, OffsetDateTime now) {
        if (this.status == target) {
            return;
        }
        if (!this.status.canTransitionTo(target)) {
            throw new InvalidPointStateException(
                    "포인트 선점 상태 전이 불가: " + this.status + " → " + target,
                    this.status.name(), "resolve");
        }
        this.status = target;
        this.resolvedAt = now;
    }

    public void assignId(Long id) {
        if (this.id != null && !this.id.equals(id)) {
            throw new InvalidPointStateException("이미 ID 가 할당된 선점입니다: " + this.id,
                    String.valueOf(this.id), "assignId");
        }
        this.id = id;
    }

    /** 아직 잔고를 붙잡고 있는가. */
    public boolean isActive() {
        return status.holdsBalance();
    }

    private static void requireReference(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidPointStateException(
                    "선점 참조(" + field + ")는 필수입니다 — 없으면 무엇을 확정·해제할지 알 수 없다",
                    "NONE", "place");
        }
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public PointHoldStatus getStatus() { return status; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
}
