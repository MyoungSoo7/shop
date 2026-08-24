package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.user.application.port.in.CreateUserUseCase;
import github.lms.lemuel.user.application.port.in.GetUserUseCase;
import github.lms.lemuel.user.application.port.in.PasswordResetUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CreateUserUseCase createUserUseCase;
    @MockitoBean GetUserUseCase getUserUseCase;
    @MockitoBean PasswordResetUseCase passwordResetUseCase;
    @MockitoBean LoadUserPort loadUserPort;
    @MockitoBean SaveUserPort saveUserPort;
    @MockitoBean PasswordHashPort passwordHashPort;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private User user(long id, String email) {
        User u = User.createWithProfile(email, "hash", UserRole.USER, "홍길동", "010-1234-5678");
        u.assignId(id);
        return u;
    }

    private void authenticate(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "n/a", List.of()));
    }

    @Test
    @DisplayName("POST /users: 회원 생성 201")
    void createUser() throws Exception {
        when(createUserUseCase.createUser(any())).thenReturn(user(1L, "new@b.com"));

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@b.com","password":"password123","role":"USER",
                                 "name":"홍길동","phoneNumber":"010-1234-5678"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@b.com"));
    }

    @Test
    @DisplayName("GET /users/me: 로그인 사용자 프로필")
    void getMe() throws Exception {
        authenticate("a@b.com");
        when(loadUserPort.findByEmail("a@b.com")).thenReturn(Optional.of(user(1L, "a@b.com")));

        mockMvc.perform(get("/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("a@b.com"));
    }

    @Test
    @DisplayName("PATCH /users/me: 프로필 수정 후 저장")
    void updateMe() throws Exception {
        authenticate("a@b.com");
        User u = user(1L, "a@b.com");
        when(loadUserPort.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(saveUserPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(patch("/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"새이름","phoneNumber":"010-9999-8888"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새이름"));
        verify(saveUserPort).save(any());
    }

    @Test
    @DisplayName("PATCH /users/me/password: 현재 비번 확인 후 변경")
    void changePassword() throws Exception {
        authenticate("a@b.com");
        User u = user(1L, "a@b.com");
        when(loadUserPort.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(passwordHashPort.matches("oldpass1", u.getPasswordHash())).thenReturn(true);
        when(passwordHashPort.matches("newpass12", u.getPasswordHash())).thenReturn(false);
        when(passwordHashPort.hash("newpass12")).thenReturn("newhash");

        mockMvc.perform(patch("/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"oldpass1","newPassword":"newpass12"}
                                """))
                .andExpect(status().isOk());
        verify(saveUserPort).save(any());
    }

    @Test
    @DisplayName("DELETE /users/me: 비번 재확인 후 비활성화")
    void withdraw() throws Exception {
        authenticate("a@b.com");
        User u = user(1L, "a@b.com");
        when(loadUserPort.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(passwordHashPort.matches("password1", u.getPasswordHash())).thenReturn(true);

        mockMvc.perform(delete("/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"password1"}
                                """))
                .andExpect(status().isOk());
        verify(saveUserPort).save(any());
    }

    @Test
    @DisplayName("GET /users/{id}: 단건 조회")
    void getUser() throws Exception {
        when(getUserUseCase.getUserById(1L)).thenReturn(user(1L, "a@b.com"));

        mockMvc.perform(get("/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("GET /users/admin/all: 전체 조회")
    void getAllUsers() throws Exception {
        when(getUserUseCase.getAllUsers()).thenReturn(List.of(user(1L, "a@b.com"), user(2L, "c@d.com")));

        mockMvc.perform(get("/users/admin/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("POST /users/password-reset/request: 재설정 요청")
    void requestPasswordReset() throws Exception {
        mockMvc.perform(post("/users/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"a@b.com"}
                                """))
                .andExpect(status().isOk());
        verify(passwordResetUseCase).requestPasswordReset("a@b.com");
    }

    @Test
    @DisplayName("POST /users/password-reset/confirm: 재설정 확정")
    void resetPassword() throws Exception {
        mockMvc.perform(post("/users/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"tok-123","newPassword":"newpass12"}
                                """))
                .andExpect(status().isOk());
        verify(passwordResetUseCase).resetPassword(any());
    }
}
