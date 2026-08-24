package github.lms.lemuel.operation.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Alertmanager webhook({@code /api/ops/webhook/**}) 보호 필터.
 *
 * <p>Alertmanager 는 임의 커스텀 헤더를 못 보내므로 기존 X-Internal-Api-Key 컨벤션 대신
 * {@code Authorization: Bearer <token>} 을 검증한다 (alertmanager.yml webhook_configs 의
 * http_config.authorization). 토큰 값은 INTERNAL_API_KEY 재사용 — 시크릿 관리 지점을 늘리지 않는다.
 *
 * <p>{@code app.ops.webhook.token} 미설정(로컬/개발) 시 통과 + 1회 경고 —
 * shared-common {@code InternalApiKeyFilter} 와 동일 시맨틱.
 */
@Component
public class OpsWebhookAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OpsWebhookAuthFilter.class);
    private static final String WEBHOOK_PREFIX = "/api/ops/webhook/";
    private static final String BEARER_PREFIX = "Bearer ";

    private final OpsProperties properties;
    private final boolean keyRequired;
    private volatile boolean warnedMissingToken = false;

    public OpsWebhookAuthFilter(OpsProperties properties,
                                @Value("${app.security.internal-key-required:false}") boolean keyRequired) {
        this.properties = properties;
        this.keyRequired = keyRequired;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // getServletPath() 는 MockMvc 등 일부 환경에서 빈 문자열 — requestURI 기준으로 매칭한다.
        String path = request.getRequestURI();
        if (path != null && path.startsWith(WEBHOOK_PREFIX)) {
            String token = properties.getWebhook().getToken();
            if (token == null || token.isBlank()) {
                // app.security.internal-key-required=true(운영) → fail-closed
                if (keyRequired) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized (webhook token not configured)");
                    return;
                }
                if (!warnedMissingToken) {
                    warnedMissingToken = true;
                    log.warn("app.ops.webhook.token 미설정 — webhook 인증이 비활성입니다. "
                            + "운영에서는 INTERNAL_API_KEY 를 operation-service 와 alertmanager 에 동일하게 설정하세요.");
                }
            } else if (!constantTimeEquals(token, extractBearer(request))) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized (webhook)");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** 타이밍 사이드채널 방지 — 토큰 비교는 상수시간(MessageDigest.isEqual). */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }
}
