package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.LoginUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.application.port.out.TokenProviderPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemoLoginServiceTest {

    @Mock LoadUserPort loadUserPort;
    @Mock SaveUserPort saveUserPort;
    @Mock PasswordHashPort passwordHashPort;
    @Mock TokenProviderPort tokenProviderPort;
    @InjectMocks DemoLoginService service;

    @Test @DisplayName("autoLogin - 기존 데모 계정이 있으면 그대로 토큰 발급")
    void autoLogin_existingUser() {
        User user = User.createWithRole("demo-user@lemuel.local", "h", UserRole.USER);
        user.assignId(5L);
        when(loadUserPort.findByEmail("demo-user@lemuel.local")).thenReturn(Optional.of(user));
        when(tokenProviderPort.generateToken("demo-user@lemuel.local", "USER", 5L)).thenReturn("jwt-token");

        LoginUseCase.LoginResult result = service.autoLogin(UserRole.USER);

        assertThat(result.token()).isEqualTo("jwt-token");
        assertThat(result.email()).isEqualTo("demo-user@lemuel.local");
        assertThat(result.role()).isEqualTo("USER");
        verify(saveUserPort, never()).save(any());
    }

    @Test @DisplayName("autoLogin - 계정이 없으면 새로 생성")
    void autoLogin_createsWhenMissing() {
        when(loadUserPort.findByEmail("demo-admin@lemuel.local")).thenReturn(Optional.empty());
        when(passwordHashPort.hash(any())).thenReturn("hashed");
        when(saveUserPort.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.assignId(7L);
            return u;
        });
        when(tokenProviderPort.generateToken("demo-admin@lemuel.local", "ADMIN", 7L)).thenReturn("admin-jwt");

        LoginUseCase.LoginResult result = service.autoLogin(UserRole.ADMIN);

        assertThat(result.token()).isEqualTo("admin-jwt");
        assertThat(result.role()).isEqualTo("ADMIN");
        verify(saveUserPort).save(any());
    }

    @Test @DisplayName("autoLogin - 기존 계정의 역할이 다르면 보정 후 저장")
    void autoLogin_correctsRoleMismatch() {
        User user = User.createWithRole("demo-manager@lemuel.local", "h", UserRole.USER);
        user.assignId(3L);
        when(loadUserPort.findByEmail("demo-manager@lemuel.local")).thenReturn(Optional.of(user));
        when(saveUserPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tokenProviderPort.generateToken("demo-manager@lemuel.local", "MANAGER", 3L)).thenReturn("mgr-jwt");

        LoginUseCase.LoginResult result = service.autoLogin(UserRole.MANAGER);

        assertThat(result.role()).isEqualTo("MANAGER");
        assertThat(user.getRole()).isEqualTo(UserRole.MANAGER);
        verify(saveUserPort).save(user);
    }

    @Test @DisplayName("autoLogin - role 이 null 이면 USER 로 기본 처리")
    void autoLogin_nullRoleDefaultsUser() {
        User user = User.createWithRole("demo-user@lemuel.local", "h", UserRole.USER);
        user.assignId(1L);
        when(loadUserPort.findByEmail("demo-user@lemuel.local")).thenReturn(Optional.of(user));
        when(tokenProviderPort.generateToken("demo-user@lemuel.local", "USER", 1L)).thenReturn("t");

        LoginUseCase.LoginResult result = service.autoLogin(null);

        assertThat(result.role()).isEqualTo("USER");
    }

    @Test @DisplayName("guestLogin - DB 저장 없이 GUEST 토큰만 발급")
    void guestLogin() {
        when(tokenProviderPort.generateToken("guest@lemuel.local", "GUEST")).thenReturn("guest-jwt");

        LoginUseCase.LoginResult result = service.guestLogin();

        assertThat(result.token()).isEqualTo("guest-jwt");
        assertThat(result.email()).isEqualTo("guest@lemuel.local");
        assertThat(result.role()).isEqualTo("GUEST");
        verify(saveUserPort, never()).save(any());
    }
}
