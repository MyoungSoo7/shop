package github.lms.lemuel.user.adapter.in.web.response;

import github.lms.lemuel.user.application.port.in.LoginUseCase;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 응답 DTO
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private String email;
    private String role;

    public static LoginResponse from(LoginUseCase.LoginResult result) {
        return new LoginResponse(
                result.token(),
                result.email(),
                result.role()
        );
    }
}
