package github.lms.lemuel.rbac.domain;
import github.lms.lemuel.rbac.domain.exception.RoleInvariantViolationException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Role 도메인 모델 (순수 POJO)
 */
public class Role {

    /** 역할 코드 규칙: 대문자 시작, 대문자/숫자/언더스코어 2~30자 */
    private static final java.util.regex.Pattern CODE_PATTERN =
            java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");

    private Long id;
    private String code;
    private String name;
    private String description;
    private boolean builtin;
    private LocalDateTime createdAt;
    private List<Permission> permissions = new ArrayList<>();

    public Role() {}

    public static Role of(Long id, String code, String name, String description,
                          boolean builtin, LocalDateTime createdAt) {
        Role role = new Role();
        role.id = id;
        role.code = code;
        role.name = name;
        role.description = description;
        role.builtin = builtin;
        role.createdAt = createdAt;
        return role;
    }

    /**
     * 커스텀 역할 생성 (builtin=false). 코드는 대문자로 정규화 후 패턴 검증.
     */
    public static Role create(String code, String name, String description) {
        Role role = new Role();
        role.code = normalizeCode(code);
        role.rename(name, description);
        role.builtin = false;
        role.createdAt = LocalDateTime.now();
        return role;
    }

    /** 역할 이름/설명 수정. 코드·builtin 은 불변. */
    public void rename(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new RoleInvariantViolationException("역할 이름은 필수입니다.");
        }
        this.name = name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
    }

    public static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new RoleInvariantViolationException("역할 코드는 필수입니다.");
        }
        String normalized = code.trim().toUpperCase();
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new RoleInvariantViolationException(
                    "역할 코드는 대문자로 시작하는 대문자/숫자/언더스코어 2~30자여야 합니다: " + normalized);
        }
        return normalized;
    }

    /** DB 부여 PK 주입(setter 대체). */
    public void assignId(Long id) { this.id = id; }

    /** 역할의 권한 목록 복원/치환(null 이면 빈 리스트로 방어). setter 대체 도메인 메서드. */
    public void replacePermissions(List<Permission> permissions) {
        this.permissions = permissions == null ? new ArrayList<>() : permissions;
    }

    // Getters
    public Long getId() { return id; }

    public String getCode() { return code; }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public boolean isBuiltin() { return builtin; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public List<Permission> getPermissions() { return permissions; }
}
