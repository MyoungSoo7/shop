package github.lms.lemuel.user.domain;

import github.lms.lemuel.common.exception.UnknownEnumValueException;
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

    @Test @DisplayName("fromString: 모르는 값은 던진다 — USER 로 떨어뜨리면 권한 문제로 둔갑한다")
    void fromString_invalid() {
        assertThatThrownBy(() -> UserRole.fromString("INVALID"))
                .isInstanceOf(UnknownEnumValueException.class)
                .hasMessageContaining("INVALID");
        assertThatThrownBy(() -> UserRole.fromString("")).isInstanceOf(UnknownEnumValueException.class);
        assertThatThrownBy(() -> UserRole.fromString(null)).isInstanceOf(UnknownEnumValueException.class);
    }

    @Test @DisplayName("fromStringOrNull: 모르는 값은 null — 조회 필터가 쓰는 관대한 쪽")
    void fromStringOrNull_lenient() {
        assertThat(UserRole.fromStringOrNull("manager")).isEqualTo(UserRole.MANAGER);
        assertThat(UserRole.fromStringOrNull("INVALID")).isNull();
        assertThat(UserRole.fromStringOrNull("  ")).isNull();
        assertThat(UserRole.fromStringOrNull(null)).isNull();
    }

    @Test @DisplayName("앞뒤 공백은 다듬는다 — 폼에서 딸려 오는 공백 하나로 400 이 나지는 않게")
    void fromString_trims() {
        assertThat(UserRole.fromString("  admin  ")).isEqualTo(UserRole.ADMIN);
    }
}
