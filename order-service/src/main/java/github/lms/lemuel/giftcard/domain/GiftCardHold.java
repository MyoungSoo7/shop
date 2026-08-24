package github.lms.lemuel.giftcard.domain;

import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 기프트카드 선점 — 입금 대기 결제가 붙잡아 두는 <b>카드 한 장의</b> 잔액 조각.
 *
 * <p>선점 한 건이 카드 여러 장에 걸칠 수 있으므로(권면가 단위로 발행되어 한 장으로 못 채우는 경우)
 * 이 레코드는 <b>(선점 근거 × 카드)</b> 단위다. 같은 참조로 여러 행이 생기는 것이 정상이다 —
 * 포인트는 계정이 하나라 참조당 한 행이었던 것과 다르다.
 *
 * <p><b>잠긴 금액을 카드에 저장하지 않는다.</b> 기프트카드에는 잔액 요약이 없고
 * (gift-card-ledger.md §3), 거기에 {@code locked} 컬럼을 더하면 "저장된 값과 선점 행의 합이
 * 어긋날 수 있다"는 손상 축을 새로 만든다 — 그 축이 없다는 것이 이 원장의 설계 자산이다.
 * 가용액은 언제나 {@code remaining − Σ(활성 선점)} 으로 계산한다.
 *
 * <p>확정 전에는 카드 잔액도, 원장 엔트리도 건드리지 않는다. 실제 차감과 {@code USE} 엔트리는
 * 입금이 확인된 순간에만 일어난다.
 */
public class GiftCardHold {

    private Long id;
    private final Long giftCardId;
    private final BigDecimal amount;
    private GiftCardHoldStatus status;
    private final String referenceType;
    private final String referenceId;
    private final OffsetDateTime createdAt;
    private OffsetDateTime resolvedAt;

    private GiftCardHold(Long id, Long giftCardId, BigDecimal amount, GiftCardHoldStatus status,
                         String referenceType, String referenceId,
                         OffsetDateTime createdAt, OffsetDateTime resolvedAt) {
        this.id = id;
        this.giftCardId = giftCardId;
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
     * <p>참조를 필수로 두는 이유: 없으면 이 선점이 어느 결제 것인지 알 수 없고, 그러면 입금이 와도
     * 무엇을 확정할지·기한이 지나도 무엇을 풀지 짚을 수 없어 <b>카드 잔액이 영영 잠긴다</b>.
     */
    public static GiftCardHold place(Long giftCardId, BigDecimal amount,
                                     String referenceType, String referenceId, OffsetDateTime now) {
        if (giftCardId == null) {
            throw new InvalidGiftCardStateException(
                    "카드 없이 선점할 수 없습니다", "NONE", "place");
        }
        requireReference(referenceType, "referenceType");
        requireReference(referenceId, "referenceId");
        return new GiftCardHold(null, giftCardId, GiftCardAmounts.require(amount, "hold"),
                GiftCardHoldStatus.ACTIVE, referenceType.trim(), referenceId.trim(), now, null);
    }

    public static GiftCardHold rehydrate(Long id, Long giftCardId, BigDecimal amount,
                                         GiftCardHoldStatus status, String referenceType,
                                         String referenceId, OffsetDateTime createdAt,
                                         OffsetDateTime resolvedAt) {
        return new GiftCardHold(id, giftCardId, amount, status, referenceType, referenceId,
                createdAt, resolvedAt);
    }

    /** 입금이 확인돼 실제 차감으로 확정한다. */
    public void capture(OffsetDateTime now) {
        resolveTo(GiftCardHoldStatus.CAPTURED, now);
    }

    /** 주문 취소 등으로 명시적으로 푼다. */
    public void release(OffsetDateTime now) {
        resolveTo(GiftCardHoldStatus.RELEASED, now);
    }

    /** 입금 기한이 지나 자동으로 풀린다. */
    public void expire(OffsetDateTime now) {
        resolveTo(GiftCardHoldStatus.EXPIRED, now);
    }

    /**
     * 종단 전이 공통 경로.
     *
     * <p>같은 상태 재적용은 멱등 no-op 이되 <b>해소 시각은 최초를 보존</b>한다 — 덮어쓰면
     * "언제 풀렸나"가 흔들려 입금 vs 만료 경합을 사후에 재구성할 수 없다.
     *
     * <p>종단끼리의 전이는 막는다. 만료 배치가 푼 선점을 뒤늦은 입금이 확정하면 이미 가용으로
     * 돌아간 카드 잔액을 한 번 더 쓰는 것이 된다.
     */
    private void resolveTo(GiftCardHoldStatus target, OffsetDateTime now) {
        if (this.status == target) {
            return;
        }
        if (!this.status.canTransitionTo(target)) {
            throw new InvalidGiftCardStateException(
                    "기프트카드 선점 상태 전이 불가: " + this.status + " → " + target,
                    this.status.name(), "resolve");
        }
        this.status = target;
        this.resolvedAt = now;
    }

    public void assignId(Long id) {
        if (this.id != null && !this.id.equals(id)) {
            throw new InvalidGiftCardStateException("이미 ID 가 할당된 선점입니다: " + this.id,
                    status.name(), "assignId");
        }
        this.id = id;
    }

    private static void requireReference(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidGiftCardStateException(
                    "선점 참조(" + field + ")는 필수입니다 — 없으면 무엇을 확정·해제할지 알 수 없다",
                    "NONE", "place");
        }
    }

    public boolean isActive() {
        return status.holdsBalance();
    }

    public Long getId() { return id; }
    public Long getGiftCardId() { return giftCardId; }
    public BigDecimal getAmount() { return amount; }
    public GiftCardHoldStatus getStatus() { return status; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
}
