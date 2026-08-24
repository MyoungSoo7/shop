package github.lms.lemuel.user.application.port.in;

import github.lms.lemuel.user.domain.User;

import java.util.List;

/**
 * 회원 조회 UseCase (Inbound Port)
 */
public interface GetUserUseCase {

    User getUserById(Long userId);

    User getUserByEmail(String email);

    List<User> getAllUsers();
}
