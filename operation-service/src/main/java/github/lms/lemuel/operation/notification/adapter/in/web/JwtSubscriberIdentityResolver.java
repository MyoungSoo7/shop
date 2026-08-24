package github.lms.lemuel.operation.notification.adapter.in.web;

import github.lms.lemuel.operation.notification.domain.NotificationTemplate;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 플랫폼 JWT(HS256, shared-common {@code JwtUtil} 과 같은 시크릿·라이브러리)를 검증해, 그 클레임을
 * 알림 허브가 라우팅하는 수신자 키로 매핑한다.
 * <ul>
 *   <li>{@code sub}(이메일) — REST/데모 경로는 이메일로 수신자를 지목한다</li>
 *   <li>{@code uid} — 정본 Outbox 이벤트는 셀러를 id 로 지목한다</li>
 *   <li>ops 메일함 — <b>ADMIN 한정</b>. 주소 필드가 없던 이벤트가 거기 쌓이는데, 열지 않으면 아무도 못 본다</li>
 * </ul>
 *
 * <p><b>왜 스프링 시큐리티가 아니라 직접 파싱하는가</b>: 브라우저 {@code EventSource} 는 요청 헤더를
 * 설정할 수 없어 토큰이 쿼리 파라미터로 온다. 표준 {@code Authorization} 헤더 기반 인증만으로는
 * 이 커넥션을 인증할 방법이 없다. 그래서 이 경로만 보안 체인에서 permitAll 로 열고
 * <b>이 리졸버가 문지기</b>가 된다(NotificationSecurityConfig 와 세트).
 *
 * <p>Fail-closed: 시크릿이 없거나 너무 짧으면 아무것도 resolve 되지 않는다. 서비스는 시크릿 없이도
 * 계속 떠야 하지만(Kafka·REST 경로는 필요 없다) 스트림이 신뢰로 데이터를 내주면 안 된다.
 */
@Component
public class JwtSubscriberIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(JwtSubscriberIdentityResolver.class);

    /** HMAC-SHA256 최소 키 길이: 256비트 = 32바이트 (shared-common 과 동일). */
    private static final int MIN_SECRET_BYTES = 32;
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String BEARER = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final SecretKey key;

    /**
     * 시크릿은 shared-common 과 같은 {@code app.jwt.secret} 에서 읽는다 — 이관 전 폴리글랏 서비스는
     * 자체 리졸버라 {@code app.security.jwt.secret} 이라는 다른 키를 썼고, 운영자가 릴랙스 바인딩
     * 이름을 스스로 알아내야 해서 스트림이 늘 503 이던 전력이 있다. 한 서비스 안으로 들어온 지금은
     * 플랫폼 공통 키 하나로 통일하는 것이 맞다.
     */
    public JwtSubscriberIdentityResolver(@Value("${app.jwt.secret:}") String secret) {
        this.key = buildKey(secret);
    }

    /** 쓸 수 있는 서명 키가 설정돼 있는가. */
    public boolean isConfigured() {
        return key != null;
    }

    private static SecretKey buildKey(String secret) {
        byte[] bytes = (secret == null ? "" : secret.trim()).getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            log.warn("app.jwt.secret is not set — the notification push stream is disabled (503)");
            return null;
        }
        if (bytes.length < MIN_SECRET_BYTES) {
            log.error("app.jwt.secret is shorter than {} bytes — treating it as unset; the push stream stays disabled",
                    MIN_SECRET_BYTES);
            return null;
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    /**
     * @return 검증된 신원. 토큰이 없거나 깨졌거나 만료됐거나 다른 키로 서명됐거나,
     *         라우팅할 신원이 하나도 없으면 null.
     */
    public SubscriberIdentity resolve(String token) {
        if (key == null) {
            return null;
        }
        String raw = token == null ? "" : token.trim();
        if (raw.isEmpty()) {
            return null;
        }

        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(raw).getPayload();
        } catch (Exception e) {
            // 서명·만료·형식 실패는 모두 "인증 안 됨"이다. 사유는 로그에만 남고 응답에는 절대 싣지 않는다.
            log.debug("rejected stream token: {}", e.toString());
            return null;
        }

        String email = claims.getSubject() == null ? "" : claims.getSubject().trim();
        String uid = readUid(claims.get("uid"));
        if (email.isEmpty() && uid == null) {
            log.debug("rejected stream token: no addressable identity (sub/uid)");
            return null;
        }

        Set<String> recipients = new LinkedHashSet<>();
        if (!email.isEmpty()) {
            recipients.add(email);
        }
        if (uid != null) {
            recipients.add(uid);
        }
        if (ADMIN_ROLE.equals(readRole(claims.get("role")))) {
            recipients.add(NotificationTemplate.OPS_FALLBACK_RECIPIENT);
        }
        return new SubscriberIdentity(email.isEmpty() ? uid : email, recipients);
    }

    private static String readUid(Object claim) {
        return switch (claim) {
            case Number number -> String.valueOf(number.longValue());
            case String s -> s.trim().isEmpty() ? null : s.trim();
            case null, default -> null;
        };
    }

    private static String readRole(Object claim) {
        if (!(claim instanceof String s)) {
            return null;
        }
        String role = s.trim().toUpperCase(Locale.ROOT);
        return role.startsWith(ROLE_PREFIX) ? role.substring(ROLE_PREFIX.length()) : role;
    }

    /**
     * 원시 토큰을 뽑는다. {@code EventSource} 는 요청 헤더를 설정할 수 없어 브라우저가 SSE 커넥션을
     * 인증할 유일한 수단이 쿼리 파라미터다. 다만 <b>헤더가 있으면 헤더가 이긴다</b> — URL 안의 토큰은
     * 액세스 로그에 남을 수 있으므로 비브라우저 클라이언트는 헤더를 써야 한다(docs/sse.md).
     */
    public static String tokenFrom(String authorization, String tokenParam) {
        String header = authorization == null ? "" : authorization.trim();
        if (header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            String value = header.substring(BEARER.length()).trim();
            return value.isEmpty() ? null : value;
        }
        if (tokenParam == null) {
            return null;
        }
        String value = tokenParam.trim();
        return value.isEmpty() ? null : value;
    }
}
