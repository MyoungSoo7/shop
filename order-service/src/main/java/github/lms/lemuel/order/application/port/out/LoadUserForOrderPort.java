package github.lms.lemuel.order.application.port.out;

import java.util.Optional;

public interface LoadUserForOrderPort {
    boolean existsById(Long userId);
    Optional<String> findEmailById(Long userId);
}
