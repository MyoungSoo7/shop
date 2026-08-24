package github.lms.lemuel.rbac.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionTest {

    @Test @DisplayName("of - 팩토리로 모든 필드 세팅")
    void of() {
        Permission p = Permission.of(1L, "ORDER_READ", "주문조회", "ORDER", "주문 조회 권한");

        assertThat(p.getId()).isEqualTo(1L);
        assertThat(p.getCode()).isEqualTo("ORDER_READ");
        assertThat(p.getName()).isEqualTo("주문조회");
        assertThat(p.getCategory()).isEqualTo("ORDER");
        assertThat(p.getDescription()).isEqualTo("주문 조회 권한");
    }

    @Test @DisplayName("setters - 개별 필드 세팅")
    void setters() {
        Permission p = Permission.of(2L, "C", "N", "CAT", "D");

        assertThat(p.getId()).isEqualTo(2L);
        assertThat(p.getCode()).isEqualTo("C");
        assertThat(p.getName()).isEqualTo("N");
        assertThat(p.getCategory()).isEqualTo("CAT");
        assertThat(p.getDescription()).isEqualTo("D");
    }
}
