package github.lms.lemuel.user.adapter.in.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordDto(
        @NotBlank(message = "토큰은 필수값입니다")
        String token,

        @NotBlank(message = "새 비밀번호는 필수값입니다")
        @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 100자 이하여야 합니다")
        String newPassword
) {
}
