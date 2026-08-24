package github.lms.lemuel.user.application.port.out;

/**
 * JWT 토큰 제공 Outbound Port
 */
public interface TokenProviderPort {

    String generateToken(String email, String role);

    /** userId(uid) claim 을 포함한 토큰 발급. userId 가 null 이면 claim 생략(GUEST 등). */
    String generateToken(String email, String role, Long userId);

    boolean validateToken(String token);

    String getEmailFromToken(String token);
}
