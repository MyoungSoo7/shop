package github.lms.lemuel.user.adapter.in.web.response;

import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 응답 DTO
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private Long id;
    private String email;
    private String role;
    private String name;
    private String phoneNumber;
    private boolean active;
    private LocalDateTime createdAt;

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                user.getName(),
                user.getPhoneNumber(),
                user.isActive(),
                user.getCreatedAt()
        );
    }
}
