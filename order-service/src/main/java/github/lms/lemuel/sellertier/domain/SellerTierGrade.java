package github.lms.lemuel.sellertier.domain;

/**
 * 셀러 등급 — order 소유(users.seller_tier 가 이 값을 담는다).
 *
 * <p>settlement 의 {@code SellerTier} 와 이름이 같지만 서비스 경계가 달라 별도 타입이다.
 * 전달은 문자열(이벤트·컬럼)로 하며, 요율·주기·홀드백 해석은 settlement 쪽 몫이다.
 */
public enum SellerTierGrade {
    NORMAL,
    VIP,
    STRATEGIC;

    /** 상위 등급일수록 큰 값 — 승급/강등 방향 판정에 쓴다. */
    public boolean isHigherThan(SellerTierGrade other) {
        return other != null && this.ordinal() > other.ordinal();
    }
}
