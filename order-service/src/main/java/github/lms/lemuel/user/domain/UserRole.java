package github.lms.lemuel.user.domain;

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

    public static UserRole fromString(String role) {
        try {
            return UserRole.valueOf(role.toUpperCase());
        } catch (Exception e) {
            return USER; // 기본값
        }
    }
}
