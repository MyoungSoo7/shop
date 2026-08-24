package github.lms.lemuel.giftcard.domain;

/**
 * 기프트카드 생명주기.
 *
 * <pre>
 * ISSUED ──활성화──▶ ACTIVE ──등록──▶ REGISTERED ──전액 소진──▶ USED_UP
 *                      │                 │
 *                      └─── 만료/정지 ────┴──▶ EXPIRED | SUSPENDED
 * </pre>
 *
 * <p>발행만 된 코드는 아직 아무에게도 가지 않았으므로 부채가 아니다. 부채는 <b>등록</b> 시점에 생긴다.
 */
public enum GiftCardStatus {
    /** 발행됨 — 코드는 만들어졌지만 아직 배포 확정 전. */
    ISSUED,
    /** 활성화됨 — 배포가 확정되어 등록 가능. */
    ACTIVE,
    /** 등록됨 — 사용자에게 귀속되어 결제에 쓸 수 있다. */
    REGISTERED,
    /** 잔액 소진. */
    USED_UP,
    /** 유효기간 경과. */
    EXPIRED,
    /** 분실·부정 신고로 정지. */
    SUSPENDED;

    /** 결제에 쓸 수 있는 상태인가 — 등록된 카드만 재원이다. */
    public boolean isSpendable() {
        return this == REGISTERED;
    }

    /** 사용자가 등록할 수 있는 상태인가. 이미 등록된 카드는 다시 등록되지 않는다. */
    public boolean isRegisterable() {
        return this == ACTIVE;
    }

    /** 더 이상 되살아나지 않는 상태인가. */
    public boolean isTerminal() {
        return this == USED_UP || this == EXPIRED;
    }
}
