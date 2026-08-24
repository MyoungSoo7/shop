package github.lms.lemuel.common.config.jwt;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setIssuer("test-issuer");
        props.setSecret("this-is-a-test-secret-key-must-be-at-least-32-bytes-long");
        props.setTtlSeconds(3600);
        jwtUtil = new JwtUtil(props);
    }

    @Test @DisplayName("토큰 생성 및 파싱")
    void generateAndParse() {
        String token = jwtUtil.generateToken("user@test.com", "USER");

        assertThat(jwtUtil.validateToken(token)).isTrue();
        assertThat(jwtUtil.getEmailFromToken(token)).isEqualTo("user@test.com");

        Claims claims = jwtUtil.parseToken(token);
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
        assertThat(claims.getIssuer()).isEqualTo("test-issuer");
    }

    @Test @DisplayName("유효하지 않은 토큰")
    void invalidToken() {
        assertThat(jwtUtil.validateToken("invalid.token.here")).isFalse();
    }

    @Test @DisplayName("빈 토큰")
    void emptyToken() {
        assertThat(jwtUtil.validateToken("")).isFalse();
    }

    @Test @DisplayName("32바이트 미만 시크릿은 예외")
    void shortSecret_throwsException() {
        JwtProperties props = new JwtProperties();
        props.setIssuer("test");
        props.setSecret("short");
        props.setTtlSeconds(3600);

        assertThatThrownBy(() -> new JwtUtil(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test @DisplayName("ADMIN 역할 토큰")
    void adminRoleToken() {
        String token = jwtUtil.generateToken("admin@test.com", "ADMIN");
        Claims claims = jwtUtil.parseToken(token);
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }
}
