package github.lms.lemuel.common.config.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    /** HMAC-SHA256 최소 키 길이: 256 bit = 32 byte */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;

    public JwtUtil(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "JWT secret must be at least 32 bytes (256 bits). Current length: " + keyBytes.length
            );
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String email, String role) {
        return generateToken(email, role, null);
    }

    /**
     * userId(uid) claim 을 포함한 토큰을 발급한다.
     * uid 가 null 이면 claim 을 생략한다(GUEST 등 식별자 없는 주체).
     */
    public String generateToken(String email, String role, Long userId) {
        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);
        Date expiration = new Date(nowMillis + (jwtProperties.getTtlSeconds() * 1000));

        var builder = Jwts.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(email)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiration);
        if (userId != null) {
            builder.claim("uid", userId);
        }
        return builder.signWith(secretKey).compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getEmailFromToken(String token) {
        return parseToken(token).getSubject();
    }
}
