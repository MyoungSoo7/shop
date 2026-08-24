package github.lms.lemuel.user.application.port.out;

import github.lms.lemuel.user.domain.User;
import java.util.List;
import java.util.Optional;

public interface LoadUserPort {
    Optional<User> findByEmail(String email);
    Optional<User> findById(Long id);
    List<User> findAll();
}
