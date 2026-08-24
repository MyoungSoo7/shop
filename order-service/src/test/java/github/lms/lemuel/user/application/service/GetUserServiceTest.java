package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GetUserServiceTest {

    @Mock LoadUserPort loadUserPort;
    @InjectMocks GetUserService service;

    @Test @DisplayName("ID로 조회 성공") void getById() {
        User user = User.createWithRole("test@test.com", "hash", UserRole.USER);
        when(loadUserPort.findById(1L)).thenReturn(Optional.of(user));
        assertThat(service.getUserById(1L).getEmail()).isEqualTo("test@test.com");
    }
    @Test @DisplayName("ID 미존재") void getById_notFound() {
        when(loadUserPort.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getUserById(999L)).isInstanceOf(UserNotFoundException.class);
    }
    @Test @DisplayName("이메일로 조회") void getByEmail() {
        User user = User.createWithRole("a@b.com", "hash", UserRole.USER);
        when(loadUserPort.findByEmail("a@b.com")).thenReturn(Optional.of(user));
        assertThat(service.getUserByEmail("a@b.com")).isNotNull();
    }
    @Test @DisplayName("이메일 미존재") void getByEmail_notFound() {
        when(loadUserPort.findByEmail("x@y.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getUserByEmail("x@y.com")).isInstanceOf(UserNotFoundException.class);
    }
    @Test @DisplayName("전체 조회") void getAll() {
        when(loadUserPort.findAll()).thenReturn(List.of());
        assertThat(service.getAllUsers()).isEmpty();
    }
}
