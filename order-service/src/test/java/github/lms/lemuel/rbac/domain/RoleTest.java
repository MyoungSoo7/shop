package github.lms.lemuel.rbac.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoleTest {

    @Test @DisplayName("of - 팩토리로 모든 필드 세팅")
    void of() {
        LocalDateTime now = LocalDateTime.now();
        Role role = Role.of(1L, "ADMIN", "관리자", "설명", true, now);

        assertThat(role.getId()).isEqualTo(1L);
        assertThat(role.getCode()).isEqualTo("ADMIN");
        assertThat(role.getName()).isEqualTo("관리자");
        assertThat(role.getDescription()).isEqualTo("설명");
        assertThat(role.isBuiltin()).isTrue();
        assertThat(role.getCreatedAt()).isEqualTo(now);
        assertThat(role.getPermissions()).isEmpty();
    }

    @Test @DisplayName("replacePermissions - null 이면 빈 리스트로 방어")
    void setPermissions_nullDefensive() {
        Role role = new Role();
        role.replacePermissions(null);
        assertThat(role.getPermissions()).isEmpty();

        Permission p = Permission.of(1L, "READ", "읽기", "cat", "d");
        role.replacePermissions(List.of(p));
        assertThat(role.getPermissions()).containsExactly(p);
    }

    @Test @DisplayName("of/assignId - 필드 복원")
    void setters() {
        LocalDateTime t = LocalDateTime.now();
        Role role = Role.of(2L, "USER", "사용자", "d", false, t);

        assertThat(role.getId()).isEqualTo(2L);
        assertThat(role.getCode()).isEqualTo("USER");
        assertThat(role.getName()).isEqualTo("사용자");
        assertThat(role.getDescription()).isEqualTo("d");
        assertThat(role.isBuiltin()).isFalse();
        assertThat(role.getCreatedAt()).isEqualTo(t);
    }
}
