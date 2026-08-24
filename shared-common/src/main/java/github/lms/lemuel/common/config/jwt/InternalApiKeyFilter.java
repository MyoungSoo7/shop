package github.lms.lemuel.common.config.jwt;

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

/**
 * 내부 서비스 간 호출(<code>/internal/**</code>) 보호 필터.
 *
 * <p>order 의 {@code /internal/recon/*} 같은 내부 API 는 gateway 라우트 predicate 에 없어 외부 미노출을
 * 전제하지만, NodePort 등으로 파드가 직접 노출되면 JWT 없이 결제/환불 합계가 새어나갈 수 있다(리뷰 #124).
 * 호출자(예: settlement {@code OrderReconClient})가 보내는 공유 시크릿 헤더({@value #HEADER})를 검증해
 * 무자격 접근을 차단한다.
 *
 * <p>{@code app.internal.api-key} 미설정(로컬/개발) 시 기존 동작을 유지(통과)하되 1회 경고한다.
 * 운영 배포(k8s/compose)는 {@code INTERNAL_API_KEY} 를 order/settlement 에 <b>동일하게</b> 주입해야 한다.
 */
@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {

    /** 호출자가 공유 시크릿을 싣는 요청 헤더 이름. */
    public static final String HEADER = "X-Internal-Api-Key";
    /**
     * 이 필터가 지키는 경로 접두. {@code /van/} 은 card-service 의 VAN 진입점(승인·매입·취소·환불)이다 —
     * 사람이 아니라 기계가 부르는 경로라 사용자 토큰이 아니라 공유 시크릿으로 가려야 한다.
     * 현재 VAN 은 실제 외부 파트너가 아니라 시뮬레이터라 내부키를 함께 쓴다. 실제 VAN 을 온보딩하면
     * 그때 별도 시크릿으로 분리해야 한다(파트너 키 유출 반경과 내부 키 유출 반경은 달라야 한다).
     */
    private static final String[] GUARDED_PREFIXES = {"/internal/", "/van/"};
    private static final Logger log = LoggerFactory.getLogger(InternalApiKeyFilter.class);

    private final String apiKey;
    private final boolean keyRequired;
    private volatile boolean warnedMissingKey = false;

    @org.springframework.beans.factory.annotation.Autowired
    public InternalApiKeyFilter(@Value("${app.internal.api-key:}") String apiKey,
                                @Value("${app.security.internal-key-required:false}") boolean keyRequired) {
        this.apiKey = apiKey;
        this.keyRequired = keyRequired;
    }

    /** 테스트/기본 편의 — keyRequired=false(기존 fail-open 동작). */
    public InternalApiKeyFilter(String apiKey) {
        this(apiKey, false);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isGuarded(resolvePath(request))) {
            if (apiKey == null || apiKey.isBlank()) {
                // 키 미설정: app.security.internal-key-required=true(운영) 면 fail-closed 로 거부,
                // 기본(false, 로컬/개발) 이면 1회 경고 후 통과.
                if (keyRequired) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                            "Unauthorized (internal API key not configured)");
                    return;
                }
                if (!warnedMissingKey) {
                    warnedMissingKey = true;
                    log.warn("app.internal.api-key 미설정 — /internal 내부 API 인증이 비활성입니다. "
                            + "운영에서는 INTERNAL_API_KEY 를 order/settlement 에 동일하게 설정하세요.");
                }
            } else if (!apiKey.equals(request.getHeader(HEADER))) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized (internal)");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 보호 판정에 쓸 경로를 정한다. {@code getServletPath()} 는 디스패처 서블릿 매핑·컨테이너에 따라
     * 빈 문자열이 될 수 있고, 그러면 이 게이트가 <b>조용히 꺼진다</b>(보호 대상 경로가 그대로 통과).
     * 그래서 비어 있으면 requestURI 에서 컨텍스트 경로를 걷어낸 값으로 폴백한다.
     */
    private static String resolvePath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath;
        }
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri != null && context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    private static boolean isGuarded(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : GUARDED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
