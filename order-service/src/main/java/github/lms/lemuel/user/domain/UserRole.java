package github.lms.lemuel.user.domain;

import github.lms.lemuel.common.exception.UnknownEnumValueException;

import java.util.Locale;

/**
 * 사용자 역할 Enum
 */
public enum UserRole {
    USER,
    ADMIN,
    MANAGER,
    // 시공관리 플랫폼 역할
    CUSTOMER,    // 일반 고객
    COMPANY,     // 업체 회원
    TECHNICIAN;  // 시공기사

    /**
     * 문자열을 역할로 옮긴다. 모르는 값이면 던진다.
     *
     * <p>예전에는 모르는 값을 조용히 {@link #USER} 로 떨어뜨렸다. 그러면 역할을 못 읽은 상황이
     * 에러가 아니라 <b>권한 없음</b>으로 보인다 — 관리자는 "관리자인데 권한이 없다"고 신고하고,
     * 서버 로그에는 아무것도 남지 않는다. 원인은 데이터인데 증상은 인가라서 찾는 데 오래 걸린다.
     *
     * <p>조건에 안 맞을 때 "필터 미적용"이 옳은 조회 파라미터라면 {@link #fromStringOrNull} 을 쓴다.
     */
    public static UserRole fromString(String role) {
        UserRole parsed = fromStringOrNull(role);
        if (parsed == null) {
            throw new UnknownEnumValueException(UserRole.class, role);
        }
        return parsed;
    }

    /** 모르는 값·빈 값이면 {@code null}. 던지지 않는 쪽이 옳은 자리에서만 쓴다. */
    public static UserRole fromStringOrNull(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        try {
            // Locale.ROOT 로 고정한다 — 터키어 로케일에서 "admin".toUpperCase() 는 "ADMİN" 이 되고,
            // 그러면 서버가 뜬 지역에 따라 같은 입력이 다르게 읽힌다.
            return UserRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
