package github.lms.lemuel.user.application.port.in;

import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;

public interface CreateUserUseCase {

    User createUser(CreateUserCommand command);

    record CreateUserCommand(
            String email,
            String rawPassword,
            UserRole role,
            String name,
            String phoneNumber
    ) {
        public CreateUserCommand(String email, String rawPassword, UserRole role) {
            this(email, rawPassword, role, null, null);
        }

        public CreateUserCommand {
            if (email == null || email.isBlank()) {
                throw new UserInvariantViolationException("Email cannot be empty");
            }
            if (rawPassword == null || rawPassword.isBlank()) {
                throw new UserInvariantViolationException("Password cannot be empty");
            }
            if (role == null) {
                role = UserRole.USER;
            }
        }
    }
}
