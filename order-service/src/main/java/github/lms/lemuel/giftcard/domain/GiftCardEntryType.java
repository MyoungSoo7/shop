package github.lms.lemuel.giftcard.domain;

/**
 * 기프트카드 원장 엔트리 유형.
 *
 * <p>금액은 <b>언제나 양수</b>이고 잔액에 미치는 방향은 이 유형이 결정한다(다른 원장과 같은 규약).
 */
public enum GiftCardEntryType {
    /** 등록 — 부채가 생기는 지점. 잔액은 권면가로 시작한다. */
    REGISTER(true),
    /** 결제 사용 — 잔액 감소. */
    USE(false),
    /** 환불 복원 — 잔액 증가. */
    RESTORE(true),
    /** 유효기간 소멸 — 잔액 감소. */
    EXPIRE(false);

    private final boolean increase;

    GiftCardEntryType(boolean increase) {
        this.increase = increase;
    }

    public boolean increasesBalance() {
        return increase;
    }
}
