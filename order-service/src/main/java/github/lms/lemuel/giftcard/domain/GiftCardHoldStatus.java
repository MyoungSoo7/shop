package github.lms.lemuel.giftcard.domain;

/**
 * 기프트카드 선점 상태.
 *
 * <p>전이: {@code ACTIVE → CAPTURED}(입금 확인 → 실제 차감) / {@code ACTIVE → RELEASED}(주문 취소 등
 * 명시적 해제) / {@code ACTIVE → EXPIRED}(입금 기한 경과로 자동 해제). 종단에서 되살리지 않는다.
 *
 * <p>포인트({@code PointHoldStatus})와 같은 모양이지만 <b>합치지 않는다</b>. 두 원장의 규칙을 공유
 * 타입으로 묶으면 한쪽 정책 변경이 다른 쪽 회귀가 된다 — 설계 문서가 원장 패턴 자체를 공통화하지
 * 않기로 한 것과 같은 이유다(gift-card-ledger.md §2).
 */
public enum GiftCardHoldStatus {
    ACTIVE,
    CAPTURED,
    RELEASED,
    EXPIRED;

    /** 아직 카드 잔액을 붙잡고 있는가. */
    public boolean holdsBalance() {
        return this == ACTIVE;
    }

    public boolean isTerminal() {
        return this != ACTIVE;
    }

    /** 허용 전이인지 — ACTIVE 에서 종단 셋 중 하나로만 간다. */
    public boolean canTransitionTo(GiftCardHoldStatus target) {
        return this == ACTIVE && target != null && target.isTerminal();
    }
}
