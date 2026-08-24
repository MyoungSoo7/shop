package github.lms.lemuel.user.adapter.in.web.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequestDto(
        @NotBlank(message = "이메일은 필수값입니다")
        @Email(message = "올바른 이메일 형식이 아닙니다")
        String email
) {
}
