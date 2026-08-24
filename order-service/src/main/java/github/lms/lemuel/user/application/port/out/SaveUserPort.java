package github.lms.lemuel.user.application.port.out;

import github.lms.lemuel.user.domain.User;

public interface SaveUserPort {
    User save(User user);
}
