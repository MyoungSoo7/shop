package github.lms.lemuel.partner.domain;

/**
 * 멤버십 상태. 삭제 대신 상태를 바꾸는 이유는 재가입 이력을 남기기 위해서다.
 *
 * <p>인가 조회는 {@link #ACTIVE} 만 본다. {@link #REMOVED} 행이 조회에 섞이면 이미 나간 사람이
 * 계속 매출을 볼 수 있다.
 */
public enum MembershipStatus {
    ACTIVE,
    REMOVED
}
