package github.lms.lemuel.point.adapter.out.user;

import github.lms.lemuel.point.application.port.out.LoadTransferRecipientPort.Recipient;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 선물 받는 이 조회 어댑터 — 이메일 오타로 남에게 포인트가 가는 것을 막는 자리다. */
@ExtendWith(MockitoExtension.class)
@DisplayName("선물 받는 이 조회 어댑터")
class TransferRecipientAdapterTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 28, 10, 0);

    @Mock LoadUserPort loadUserPort;
    @InjectMocks TransferRecipientAdapter adapter;

    private static User user(String email, String name, boolean active, MembershipStatus status) {
        return User.rehydrate(7L, email, "hash", UserRole.USER, name, "010-0000-0000",
                active, status, T0, T0);
    }

    private static User approved(String email, String name) {
        return user(email, name, true, MembershipStatus.APPROVED);
    }

    @Nested
    @DisplayName("받는 이 확인")
    class FindActiveRecipient {

        @Test
        @DisplayName("이메일과 이름이 모두 맞으면 찾는다")
        void findsWhenBothMatch() {
            when(loadUserPort.findByEmail("friend@example.com"))
                    .thenReturn(Optional.of(approved("friend@example.com", "김받는")));

            assertThat(adapter.findActiveRecipient("friend@example.com", "김받는"))
                    .contains(new Recipient(7L, "김받는"));
        }

        @Test
        @DisplayName("앞뒤 공백은 걷어 내고 묻는다")
        void stripsInputs() {
            when(loadUserPort.findByEmail("friend@example.com"))
                    .thenReturn(Optional.of(approved("friend@example.com", "김받는")));

            assertThat(adapter.findActiveRecipient("  friend@example.com  ", "  김받는  "))
                    .isPresent();
        }

        @Test
        @DisplayName("이메일을 소문자로 접지 않는다 — 저장소가 입력한 그대로 담고 로그인도 같은 값으로 찾는다")
        void doesNotLowercaseEmail() {
            when(loadUserPort.findByEmail("Friend@Example.com"))
                    .thenReturn(Optional.of(approved("Friend@Example.com", "김받는")));

            assertThat(adapter.findActiveRecipient("Friend@Example.com", "김받는")).isPresent();
            verify(loadUserPort).findByEmail("Friend@Example.com");
        }

        @Test
        @DisplayName("이름이 다르면 찾지 못한다 — 이 칸이 있는 이유가 오타 방지다")
        void rejectsNameMismatch() {
            when(loadUserPort.findByEmail("friend@example.com"))
                    .thenReturn(Optional.of(approved("friend@example.com", "김받는")));

            assertThat(adapter.findActiveRecipient("friend@example.com", "이딴사람")).isEmpty();
        }

        @Test
        @DisplayName("비활성 회원은 받을 수 없다 — 열리지 않는 계정에 포인트가 갇힌다")
        void rejectsInactive() {
            when(loadUserPort.findByEmail("friend@example.com"))
                    .thenReturn(Optional.of(user("friend@example.com", "김받는",
                            false, MembershipStatus.APPROVED)));

            assertThat(adapter.findActiveRecipient("friend@example.com", "김받는")).isEmpty();
        }

        @Test
        @DisplayName("승인 대기·정지·반려 회원도 받을 수 없다")
        void rejectsUnusableMembership() {
            for (MembershipStatus status : new MembershipStatus[]{
                    MembershipStatus.PENDING, MembershipStatus.SUSPENDED, MembershipStatus.REJECTED}) {
                when(loadUserPort.findByEmail("friend@example.com"))
                        .thenReturn(Optional.of(user("friend@example.com", "김받는", true, status)));

                assertThat(adapter.findActiveRecipient("friend@example.com", "김받는"))
                        .as("membershipStatus=%s", status)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("없는 회원이면 빈 값이다")
        void emptyWhenUnknown() {
            when(loadUserPort.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

            assertThat(adapter.findActiveRecipient("nobody@example.com", "김받는")).isEmpty();
        }

        @Test
        @DisplayName("이메일이나 이름이 비면 저장소를 부르지 않는다")
        void shortCircuitsOnBlankInput() {
            assertThat(adapter.findActiveRecipient(null, "김받는")).isEmpty();
            assertThat(adapter.findActiveRecipient("  ", "김받는")).isEmpty();
            assertThat(adapter.findActiveRecipient("friend@example.com", null)).isEmpty();
            assertThat(adapter.findActiveRecipient("friend@example.com", " ")).isEmpty();

            verify(loadUserPort, never()).findByEmail(org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("상대방 이름 조회")
    class FindNameById {

        @Test
        @DisplayName("식별자로 이름을 읽는다")
        void readsName() {
            when(loadUserPort.findById(7L)).thenReturn(Optional.of(approved("f@example.com", "김받는")));

            assertThat(adapter.findNameById(7L)).contains("김받는");
        }

        @Test
        @DisplayName("탈퇴·삭제로 사라진 회원이면 빈 값이다 — 이력은 남고 이름만 없다")
        void emptyWhenGone() {
            when(loadUserPort.findById(9L)).thenReturn(Optional.empty());

            assertThat(adapter.findNameById(9L)).isEmpty();
        }

        @Test
        @DisplayName("식별자가 없으면 저장소를 부르지 않는다")
        void shortCircuitsOnNull() {
            assertThat(adapter.findNameById(null)).isEmpty();

            verify(loadUserPort, never()).findById(org.mockito.ArgumentMatchers.anyLong());
        }
    }
}
