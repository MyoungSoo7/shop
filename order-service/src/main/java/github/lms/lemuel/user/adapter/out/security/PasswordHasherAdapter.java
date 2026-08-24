package github.lms.lemuel.user.adapter.out.security;

import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt 기반 비밀번호 해싱 어댑터
 */
@Component
@RequiredArgsConstructor
public class PasswordHasherAdapter implements PasswordHashPort {

    private final PasswordEncoder passwordEncoder;

    @Override
    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String hashedPassword) {
        return passwordEncoder.matches(rawPassword, hashedPassword);
    }
}
