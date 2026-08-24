package github.lms.lemuel.user.adapter.out.security;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.user.application.port.out.TokenProviderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JWT 토큰 제공 어댑터 (기존 JwtUtil 래핑)
 */
@Component
@RequiredArgsConstructor
public class JwtTokenProviderAdapter implements TokenProviderPort {

    private final JwtUtil jwtUtil;

    @Override
    public String generateToken(String email, String role) {
        return jwtUtil.generateToken(email, role);
    }

    @Override
    public String generateToken(String email, String role, Long userId) {
        return jwtUtil.generateToken(email, role, userId);
    }

    @Override
    public boolean validateToken(String token) {
        return jwtUtil.validateToken(token);
    }

    @Override
    public String getEmailFromToken(String token) {
        return jwtUtil.getEmailFromToken(token);
    }
}
