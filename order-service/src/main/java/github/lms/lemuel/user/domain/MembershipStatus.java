package github.lms.lemuel.user.domain;

/**
 * 회원 승인 상태 Enum
 *
 * 업체 회원/시공기사는 가입 후 관리자 승인을 거쳐야 서비스 이용 가능.
 * 상태머신: PENDING → APPROVED → SUSPENDED ; → REJECTED
 */
public enum MembershipStatus {
    PENDING,    // 승인 대기
    APPROVED,   // 승인 완료 (서비스 이용 가능)
    REJECTED,   // 반려
    SUSPENDED;  // 정지

    public static MembershipStatus fromString(String status) {
        try {
            return MembershipStatus.valueOf(status.toUpperCase());
        } catch (Exception e) {
            return PENDING; // 기본값
        }
    }

    public boolean canUseService() {
        return this == APPROVED;
    }
}
