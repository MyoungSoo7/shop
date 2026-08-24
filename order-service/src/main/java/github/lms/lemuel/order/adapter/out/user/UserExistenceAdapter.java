package github.lms.lemuel.order.adapter.out.user;

import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Order에서 User 존재 여부 확인하는 Adapter
 * User 모듈의 Port를 사용하여 의존성 분리
 */
@Component
@RequiredArgsConstructor
public class UserExistenceAdapter implements LoadUserForOrderPort {

    private final LoadUserPort loadUserPort;

    @Override
    public boolean existsById(Long userId) {
        return loadUserPort.findById(userId).isPresent();
    }

    @Override
    public Optional<String> findEmailById(Long userId) {
        return loadUserPort.findById(userId).map(User::getEmail);
    }
}
