package github.lms.lemuel.user.domain;
import github.lms.lemuel.user.domain.exception.InvalidMembershipStateException;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    @Test @DisplayName("create 정상 생성 - 기본 role=USER")
    void create_defaultRole() {
        User u = User.create("a@b.com", "hash");

        assertThat(u.getEmail()).isEqualTo("a@b.com");
        assertThat(u.getPasswordHash()).isEqualTo("hash");
        assertThat(u.getRole()).isEqualTo(UserRole.USER);
        assertThat(u.isAdmin()).isFalse();
    }

    @Test @DisplayName("createWithRole 로 ADMIN 지정")
    void createWithRole_admin() {
        User u = User.createWithRole("admin@x.com", "hash", UserRole.ADMIN);

        assertThat(u.isAdmin()).isTrue();
        assertThat(u.getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test @DisplayName("이메일 형식 검증 실패")
    void invalidEmail() {
        assertThatThrownBy(() -> User.create("not-an-email", "hash"))
                .isInstanceOf(UserInvariantViolationException.class)
                .hasMessageContaining("Invalid email");
    }

    @Test @DisplayName("비어있는 이메일 검증 실패")
    void emptyEmail() {
        assertThatThrownBy(() -> User.create("", "hash"))
                .isInstanceOf(UserInvariantViolationException.class);
    }

    @Test @DisplayName("비어있는 비밀번호 해시 검증 실패")
    void emptyPasswordHash() {
        assertThatThrownBy(() -> User.create("a@b.com", ""))
                .isInstanceOf(UserInvariantViolationException.class);
    }

    @Test @DisplayName("changeRole / updatePassword 시 updatedAt 갱신")
    void mutations() {
        User u = User.create("a@b.com", "hash");
        var before = u.getUpdatedAt();

        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        u.changeRole(UserRole.ADMIN);
        assertThat(u.getUpdatedAt()).isAfter(before);

        before = u.getUpdatedAt();
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        u.updatePassword("new-hash");
        assertThat(u.getPasswordHash()).isEqualTo("new-hash");
        assertThat(u.getUpdatedAt()).isAfter(before);
    }
}
