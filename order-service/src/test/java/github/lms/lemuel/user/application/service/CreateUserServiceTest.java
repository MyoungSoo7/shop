package github.lms.lemuel.user.application.service;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;

import github.lms.lemuel.user.application.port.in.CreateUserUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.DuplicateEmailException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateUserServiceTest {

    @Mock LoadUserPort loadUserPort;
    @Mock SaveUserPort saveUserPort;
    @Mock PasswordHashPort passwordHashPort;
    @Mock github.lms.lemuel.user.application.port.out.PublishUserEventPort publishUserEventPort;
    @InjectMocks CreateUserService service;

    @Test @DisplayName("정상 회원가입 - 비밀번호 해싱 + User 저장")
    void createUser_success() {
        when(loadUserPort.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(passwordHashPort.hash("raw-pw")).thenReturn("hashed-pw");
        when(saveUserPort.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.assignId(1L);
            return u;
        });

        User result = service.createUser(new CreateUserUseCase.CreateUserCommand(
                "new@example.com", "raw-pw", UserRole.USER));

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getPasswordHash()).isEqualTo("hashed-pw");
        assertThat(result.getRole()).isEqualTo(UserRole.USER);
        verify(passwordHashPort).hash("raw-pw");
    }

    @Test @DisplayName("중복 이메일 → DuplicateEmailException + 저장 없음")
    void createUser_duplicateEmail() {
        when(loadUserPort.findByEmail("dup@example.com"))
                .thenReturn(Optional.of(new User(1L, "dup@example.com", "x", UserRole.USER, null, null)));

        assertThatThrownBy(() -> service.createUser(new CreateUserUseCase.CreateUserCommand(
                "dup@example.com", "raw-pw", UserRole.USER)))
                .isInstanceOf(DuplicateEmailException.class);
        verify(passwordHashPort, never()).hash(any());
        verify(saveUserPort, never()).save(any());
    }

    @Test @DisplayName("Command 검증: email 누락")
    void command_missingEmail() {
        assertThatThrownBy(() -> new CreateUserUseCase.CreateUserCommand("", "pw", UserRole.USER))
                .isInstanceOf(UserInvariantViolationException.class);
    }

    @Test @DisplayName("Command 검증: role 누락 시 USER 기본값")
    void command_missingRole() {
        var command = new CreateUserUseCase.CreateUserCommand("a@b.c", "pw", null);
        assertThat(command.role()).isEqualTo(UserRole.USER);
    }
}
