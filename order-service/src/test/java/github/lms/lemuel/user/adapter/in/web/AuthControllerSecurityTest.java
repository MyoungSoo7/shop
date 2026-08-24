package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtAuthenticationFilter;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.config.jwt.SecurityConfig;
import github.lms.lemuel.user.application.port.in.DemoLoginUseCase;
import github.lms.lemuel.user.application.port.in.LoginUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig 의 /auth/login permitAll 규칙을 실제 필터체인으로 검증.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@ActiveProfiles("test")
class AuthControllerSecurityTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean LoginUseCase loginUseCase;
    @MockitoBean DemoLoginUseCase demoLoginUseCase;
    @MockitoBean JwtUtil jwtUtil;

    @Test
    @DisplayName("POST /auth/login 는 permitAll — 미인증으로도 도메인까지 전달된다")
    void loginEndpointIsPublic() throws Exception {
        when(loginUseCase.login(any()))
                .thenReturn(new LoginUseCase.LoginResult("token-123", "user@test.com", "USER"));

        // Boot 4 + Jackson 3 에서 WebMvcTest 슬라이스에 ObjectMapper 가 기본 주입되지 않아
        // JSON 은 하드코딩된 리터럴을 사용한다 (permitAll 규칙 검증만이 목적).
        String body = """
                {"email":"user@test.com","password":"password123"}
                """;

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
