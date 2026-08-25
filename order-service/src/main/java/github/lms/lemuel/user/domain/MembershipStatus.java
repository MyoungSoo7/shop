package github.lms.lemuel.user.domain;

import github.lms.lemuel.common.exception.UnknownEnumValueException;

import java.util.Locale;

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

    /**
     * 문자열을 승인 상태로 옮긴다. 모르는 값이면 던진다.
     *
     * <p>예전 기본값은 {@link #PENDING} 이었다 — 승인된 회원이 "승인 대기"로 둔갑해
     * 서비스를 못 쓰게 되는데, 화면에는 정상적인 대기 안내가 뜬다. DB 는
     * {@code chk_users_membership_status} 로 네 값만 허용하므로, 여기 걸린다는 건
     * 제약이 깨졌거나 enum 에 없는 상태가 새로 생겼다는 뜻이다.
     */
    public static MembershipStatus fromString(String status) {
        MembershipStatus parsed = fromStringOrNull(status);
        if (parsed == null) {
            throw new UnknownEnumValueException(MembershipStatus.class, status);
        }
        return parsed;
    }

    /** 모르는 값·빈 값이면 {@code null}. 조회 필터처럼 던지지 않는 쪽이 옳은 자리에서만 쓴다. */
    public static MembershipStatus fromStringOrNull(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return MembershipStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean canUseService() {
        return this == APPROVED;
    }
}
