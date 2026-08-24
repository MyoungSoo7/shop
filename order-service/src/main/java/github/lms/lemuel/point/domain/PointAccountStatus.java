package github.lms.lemuel.point.domain;

/**
 * 포인트 계정 상태.
 *
 * <p>{@code SUSPENDED} 는 <b>사용만</b> 막고 적립은 허용한다 — 부정거래 조사 중이라는 이유로
 * 정상 주문의 적립까지 막으면 고객이 손해를 본다. {@code CLOSED} 는 잔액 0 일 때만 도달할 수 있다.
 */
public enum PointAccountStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED;

    /** 포인트를 사용(차감)할 수 있는 상태인가. */
    public boolean allowsUse() {
        return this == ACTIVE;
    }

    /** 포인트를 적립·복원할 수 있는 상태인가 — 정지 계정도 적립은 받는다. */
    public boolean allowsGrant() {
        return this != CLOSED;
    }
}
