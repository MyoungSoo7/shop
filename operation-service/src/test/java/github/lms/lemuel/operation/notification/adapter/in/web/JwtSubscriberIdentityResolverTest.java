package github.lms.lemuel.operation.notification.adapter.in.web;

import github.lms.lemuel.operation.notification.domain.NotificationTemplate;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 푸시 스트림은 신원을 틀리면 <b>남의 데이터를 건네주는</b> 유일한 표면이다 — 그래서 신원은
 * 검증된 JWT 에서만 나온다.
 *
 * <p>이관 후에도 이 계약이 유지되는지가 핵심이다: 보안 체인이 이 경로를 permitAll 로 열어 두므로
 * (EventSource 가 헤더를 못 쓰기 때문), 이 리졸버가 뚫리면 게이트가 아예 없는 것과 같다.
 */
class JwtSubscriberIdentityResolverTest {

    private static final String SECRET = "notification-slice-test-secret-32bytes+";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final JwtSubscriberIdentityResolver resolver = new JwtSubscriberIdentityResolver(SECRET);

    private static String token() {
        return token("seller@lemuel.co.kr", "USER", 42L, 60, KEY);
    }

    private static String token(String email, String role, Long uid, long ttlSeconds, SecretKey signingKey) {
        long now = System.currentTimeMillis();
        JwtBuilder builder = Jwts.builder()
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlSeconds * 1000));
        if (email != null) {
            builder.subject(email);
        }
        if (role != null) {
            builder.claim("role", role);
        }
        if (uid != null) {
            builder.claim("uid", uid);
        }
        return builder.signWith(signingKey).compact();
    }

    @Test
    @DisplayName("유효한 토큰은 이메일과 userId 를 신원으로 준다")
    void validTokenYieldsEmailAndUserIdAsIdentities() {
        SubscriberIdentity identity = resolver.resolve(token());

        assertNotNull(identity);
        assertEquals(Set.of("seller@lemuel.co.kr", "42"), identity.recipients());
        assertEquals("seller@lemuel.co.kr", identity.subject());
    }

    @Test
    @DisplayName("ADMIN 은 ops 폴백 메일함도 함께 받는다")
    void adminAdditionallyReceivesTheOpsFallbackMailbox() {
        SubscriberIdentity identity = resolver.resolve(token("ops@lemuel.co.kr", "ADMIN", 1L, 60, KEY));

        assertNotNull(identity);
        assertTrue(identity.recipients().contains(NotificationTemplate.OPS_FALLBACK_RECIPIENT),
                "주소를 파생할 수 없던 이벤트를 볼 사람이 관리자뿐이다");
    }

    @Test
    @DisplayName("ROLE_ 접두사가 붙은 역할도 ADMIN 으로 인정된다")
    void rolePrefixIsStripped() {
        SubscriberIdentity identity = resolver.resolve(token("ops@lemuel.co.kr", "ROLE_ADMIN", 1L, 60, KEY));

        assertNotNull(identity);
        assertTrue(identity.recipients().contains(NotificationTemplate.OPS_FALLBACK_RECIPIENT));
    }

    @Test
    @DisplayName("일반 사용자는 ops 폴백 메일함을 절대 받지 않는다")
    void nonAdminNeverReceivesTheOpsFallbackMailbox() {
        SubscriberIdentity identity = resolver.resolve(token());

        assertNotNull(identity);
        assertFalse(identity.recipients().contains(NotificationTemplate.OPS_FALLBACK_RECIPIENT));
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 거부된다")
    void tokenSignedWithAnotherKeyIsRejected() {
        SecretKey foreign = Keys.hmacShaKeyFor("a-completely-different-secret-32bytes".getBytes(StandardCharsets.UTF_8));

        assertNull(resolver.resolve(token("seller@lemuel.co.kr", "USER", 42L, 60, foreign)));
    }

    @Test
    @DisplayName("만료된 토큰은 거부된다")
    void expiredTokenIsRejected() {
        assertNull(resolver.resolve(token("seller@lemuel.co.kr", "USER", 42L, -60, KEY)));
    }

    @Test
    @DisplayName("쓰레기 토큰·빈 토큰은 거부된다")
    void garbageAndMissingTokensAreRejected() {
        assertNull(resolver.resolve("not.a.jwt"));
        assertNull(resolver.resolve(null));
        assertNull(resolver.resolve("  "));
    }

    @Test
    @DisplayName("라우팅할 신원이 없는 토큰은 거부된다")
    void tokenCarryingNoAddressableIdentityIsRejected() {
        assertNull(resolver.resolve(token(null, "USER", null, 60, KEY)));
    }

    @Test
    @DisplayName("이메일 없이 uid 만 있으면 uid 가 subject 가 된다")
    void uidOnlyTokenUsesUidAsSubject() {
        SubscriberIdentity identity = resolver.resolve(token(null, "USER", 7L, 60, KEY));

        assertNotNull(identity);
        assertEquals("7", identity.subject());
        assertEquals(Set.of("7"), identity.recipients());
    }

    @Test
    @DisplayName("시크릿이 없는 리졸버는 fail-closed 다")
    void resolverWithNoSecretIsFailClosed() {
        JwtSubscriberIdentityResolver unconfigured = new JwtSubscriberIdentityResolver("");

        assertFalse(unconfigured.isConfigured());
        // 완벽히 유효한 토큰도 아무것도 resolve 되지 않는다: 키가 없으면 검증할 방법이 없고,
        // 추측은 선택지가 아니다.
        assertNull(unconfigured.resolve(token()));
    }

    @Test
    @DisplayName("32바이트 미만 시크릿은 미설정으로 취급된다")
    void secretShorterThan32BytesCountsAsUnconfigured() {
        JwtSubscriberIdentityResolver weak = new JwtSubscriberIdentityResolver("too-short");

        assertFalse(weak.isConfigured());
        assertNull(weak.resolve(token()));
    }

    @Test
    @DisplayName("토큰은 Authorization 헤더 또는 쿼리 파라미터에서 읽는다 — 헤더 우선")
    void tokenIsReadFromHeaderOrQueryParam() {
        String raw = token();

        assertEquals(raw, JwtSubscriberIdentityResolver.tokenFrom("Bearer " + raw, null));
        // EventSource 는 헤더를 설정할 수 없어 쿼리 파라미터 폴백이 필요하다.
        assertEquals(raw, JwtSubscriberIdentityResolver.tokenFrom(null, raw));
        // 둘 다 있으면 헤더가 이긴다.
        assertEquals(raw, JwtSubscriberIdentityResolver.tokenFrom("Bearer " + raw, "other"));
        assertNull(JwtSubscriberIdentityResolver.tokenFrom(null, null));
        assertNull(JwtSubscriberIdentityResolver.tokenFrom("Basic abc", null));
        assertNull(JwtSubscriberIdentityResolver.tokenFrom("Bearer   ", null));
        assertNull(JwtSubscriberIdentityResolver.tokenFrom(null, "   "));
    }
}
