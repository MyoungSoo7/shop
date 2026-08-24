package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.DemoLoginUseCase;
import github.lms.lemuel.user.application.port.in.LoginUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.application.port.out.TokenProviderPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모/자동로그인 구현.
 *
 * 데모 계정 이메일 규약: demo-{role-lowercase}@lemuel.local
 *   - demo-user@lemuel.local        (USER)
 *   - demo-manager@lemuel.local     (MANAGER)
 *   - demo-admin@lemuel.local       (ADMIN)
 *
 * 게스트는 DB 사용자 없이 JWT 만 발급.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoLoginService implements DemoLoginUseCase {

    private static final String DEMO_PASSWORD_RAW = "demo-only-not-real-secret";
    private static final String GUEST_EMAIL       = "guest@lemuel.local";

    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;
    private final PasswordHashPort passwordHashPort;
    private final TokenProviderPort tokenProviderPort;

    @Override
    @Transactional
    public LoginUseCase.LoginResult autoLogin(UserRole requestedRole) {
        // 파라미터 재할당 대신 새 final 변수에 담아 람다 캡처 가능하게 함.
        final UserRole role = requestedRole == null ? UserRole.USER : requestedRole;
        String email = "demo-" + role.name().toLowerCase() + "@lemuel.local";

        User user = loadUserPort.findByEmail(email)
                .orElseGet(() -> {
                    log.info("[demo] 데모 계정 자동 생성: email={}, role={}", email, role);
                    User created = User.createWithRole(
                            email,
                            passwordHashPort.hash(DEMO_PASSWORD_RAW),
                            role
                    );
                    return saveUserPort.save(created);
                });

        // 기존 데모 계정 역할이 바뀌어 있으면 보정 (드물지만 안전망)
        if (user.getRole() != role) {
            user.changeRole(role);
            user = saveUserPort.save(user);
        }

        String token = tokenProviderPort.generateToken(user.getEmail(), user.getRole().name(), user.getId());
        return new LoginUseCase.LoginResult(token, user.getEmail(), user.getRole().name());
    }

    @Override
    public LoginUseCase.LoginResult guestLogin() {
        // DB 사용자 생성 없이 토큰만 발급 — role=GUEST 는 보호된 엔드포인트 호출 시 권한 부족으로 거절됨
        String token = tokenProviderPort.generateToken(GUEST_EMAIL, "GUEST");
        return new LoginUseCase.LoginResult(token, GUEST_EMAIL, "GUEST");
    }
}
