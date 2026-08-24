package github.lms.lemuel.user.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class UserRoleTest {

    @Test @DisplayName("enum에 6개의 값이 존재한다 (이커머스 USER/ADMIN/MANAGER + 시공 CUSTOMER/COMPANY/TECHNICIAN)")
    void values_count() {
        assertThat(UserRole.values()).hasSize(6);
        assertThat(UserRole.values()).containsExactly(
                UserRole.USER, UserRole.ADMIN, UserRole.MANAGER,
                UserRole.CUSTOMER, UserRole.COMPANY, UserRole.TECHNICIAN);
    }

    @Test @DisplayName("fromString: 대문자 변환")
    void fromString_uppercase() {
        assertThat(UserRole.fromString("ADMIN")).isEqualTo(UserRole.ADMIN);
        assertThat(UserRole.fromString("USER")).isEqualTo(UserRole.USER);
        assertThat(UserRole.fromString("MANAGER")).isEqualTo(UserRole.MANAGER);
    }

    @Test @DisplayName("fromString: 소문자 변환")
    void fromString_lowercase() {
        assertThat(UserRole.fromString("admin")).isEqualTo(UserRole.ADMIN);
        assertThat(UserRole.fromString("user")).isEqualTo(UserRole.USER);
    }

    @Test @DisplayName("fromString: 유효하지 않은 값이면 USER 반환")
    void fromString_invalid() {
        assertThat(UserRole.fromString("INVALID")).isEqualTo(UserRole.USER);
        assertThat(UserRole.fromString("")).isEqualTo(UserRole.USER);
    }
}
