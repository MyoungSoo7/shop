package github.lms.lemuel.operation.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * 운영 콘솔({@code /api/ops/**}) 접근 로그.
 *
 * <p><b>왜 필요한가</b>: 이 서비스는 <b>바꾸는 조작</b>만 감사에 남긴다({@code @Auditable}).
 * 그런데 운영 콘솔에서 실제로 민감한 것의 절반은 <b>보는 조작</b>이다 — 누가 어느 회원을
 * 검색했는지, 어느 기간의 감사 기록을 훑었는지는 아무 데도 남지 않았다. 감사 테이블에 넣기엔
 * 양이 많고 대부분 무의미하지만, 로그 스트림에는 있어야 한다. 사고가 나면 그때 되짚는 것은
 * 대개 "그 사람이 그날 무엇을 열어 봤나"이지 "무엇을 고쳤나"가 아니다.
 *
 * <p><b>실패한 접근이 특히 중요하다.</b> 그래서 이 필터는 보안 체인 <b>안쪽</b>, 인증 필터들을
 * 감싸는 자리에 선다({@code OperationSecurityConfig}). 컨트롤러 쪽에 두면 401·403 으로 걸러진
 * 요청은 아예 도달하지 않아 — 권한 없는 사람이 관리자 API 를 두드린 사실이 통째로 보이지 않는다.
 * 이 위치는 인증·인가 거부를 모두 응답 코드로 되받고, {@code finally} 시점에는 아직
 * {@code SecurityContextHolderFilter} 가 컨텍스트를 비우기 전이라 행위자도 채워져 있다.
 * 체인이 {@code securityMatcher("/api/ops/**")} 로 잘려 있으므로 경로 판정을 따로 하지 않는다.
 *
 * <p><b>쿼리스트링은 필드명으로 마스킹한다.</b> 값 패턴(이메일·전화·카드)으로만 가리는
 * 기존 로그 마스킹({@code PIIMaskingConverter})은 한국어 이름·주소처럼 형태가 없는 값을
 * 못 잡는다. 파라미터는 이름이 무엇인지 우리가 알고 있으므로, 값을 뜯어보는 대신 이름으로
 * 판정한다. 과하게 가리는 쪽으로 기운다 — 접근 로그는 ELK 로 흘러 열람 범위가 넓고,
 * 여기서 무엇을 검색했는지 정확히 알아야 하는 상황은 감사 테이블이 답한다
 * ({@code OperationAuditLogExportService} 가 반출 조건을 그쪽에 남긴다).
 *
 * <p><b>스프링 빈이 아니다.</b> {@code Filter} 를 빈으로 올리면 스프링 부트가 이를 서블릿
 * 컨테이너에도 자동 등록해, 보안 체인 안의 한 번과 합쳐 <b>요청 한 건이 두 줄로 찍힌다</b>.
 * 접근 로그에서 중복 행은 단순한 잡음이 아니라 건수를 두 배로 세게 만든다. 그래서 이 클래스는
 * 설정에서 직접 생성해 체인에만 꽂는다({@code RateLimitFilter} 등이 {@code FilterRegistrationBean}
 * 으로 자동 등록을 눌러 두는 것과 같은 문제를, 빈으로 만들지 않는 쪽으로 푼다).
 */
public class OpsAccessLogFilter extends OncePerRequestFilter {

    /** 로거 이름을 클래스명이 아니라 채널명으로 둔다 — 접근 로그만 따로 라우팅·보존하기 위해서다. */
    private static final Logger log = LoggerFactory.getLogger("ops.access");

    static final String MASK = "****";
    private static final String ANONYMOUS = "anonymous";

    /**
     * 이 조각이 파라미터 이름에 들어 있으면 값을 가린다(소문자 부분일치).
     *
     * <p>부분일치라 {@code accountId} 처럼 가릴 필요까진 없는 것도 걸린다. 그대로 둔다 —
     * 접근 로그에서 덜 가려 새는 쪽의 대가가 더 가려 불편한 쪽보다 훨씬 크고, 정확한 조건이
     * 필요한 조회는 감사 테이블에 남기 때문이다.
     */
    private static final List<String> PRIVACY_FIELDS = List.of(
            "password", "passwd", "pwd", "secret", "token", "apikey", "credential",
            "email", "phone", "mobile", "cellphone", "tel",
            "ssn", "rrn", "jumin", "birth",
            "card", "account", "iban",
            "addr", "zip", "postcode",
            "username", "realname", "fullname", "recipient");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long tookMs = (System.nanoTime() - startedAt) / 1_000_000L;
            // 로깅이 요청을 깨뜨리는 일은 없어야 한다. 접근 로그는 부가 기능이다.
            try {
                log.info("[OPS] {} {}{} actor={} ip={} status={} took={}ms",
                        request.getMethod(),
                        request.getRequestURI(),
                        maskedQueryString(request.getQueryString()),
                        currentActor(),
                        clientIp(request),
                        response.getStatus(),
                        tookMs);
            } catch (RuntimeException e) {
                log.warn("[OPS] 접근 로그 기록 실패: {}", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 쿼리스트링을 {@code ?a=1&email=****} 형태로 되돌린다. 비어 있으면 빈 문자열.
     *
     * <p>디코딩하지 않는다 — 로그에 원문 그대로 남는 편이 재현에 유리하고, 디코딩은
     * 잘못된 퍼센트 인코딩에서 예외를 던져 로깅이 요청을 건드리게 만든다.
     */
    static String maskedQueryString(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        StringJoiner joined = new StringJoiner("&", "?", "");
        for (String pair : rawQuery.split("&", -1)) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                // 값 없는 플래그 파라미터. 이름만 남으므로 가릴 것이 없다.
                joined.add(pair);
                continue;
            }
            String name = pair.substring(0, eq);
            joined.add(isPrivacyField(name) ? name + "=" + MASK : pair);
        }
        return joined.toString();
    }

    /** 파라미터 이름이 개인정보를 담는 자리인가. */
    static boolean isPrivacyField(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String field : PRIVACY_FIELDS) {
            if (lower.contains(field)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 인증된 주체 이름. 없으면 {@code anonymous}.
     *
     * <p>운영자 이메일 자체는 가리지 않는다. 이 값은 사용자가 보낸 입력이 아니라 <b>토큰이
     * 증명한 행위자</b>이며, 누가 했는지를 지우면 접근 로그가 존재할 이유가 사라진다.
     */
    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ANONYMOUS;
        }
        Object principal = auth.getPrincipal();
        if (principal == null || "anonymousUser".equals(principal)) {
            return ANONYMOUS;
        }
        return principal.toString();
    }

    /** 프록시 뒤라 {@code X-Forwarded-For} 첫 값이 원 클라이언트다. AuditContextFilter 와 같은 규칙. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            return sanitizeForLog(first.trim());
        }
        return sanitizeForLog(request.getRemoteAddr());
    }

    /** IPv6 + 스코프 표기까지 담기는 길이. 넘어가는 값은 IP 가 아니라 다른 무언가다. */
    private static final int MAX_IP_LENGTH = 45;

    /**
     * IP 자리에 올 수 있는 앞부분만 취한다.
     *
     * <p>{@code X-Forwarded-For} 는 클라이언트가 마음대로 채우는 값이라, 줄바꿈을 넣으면 가짜
     * 접근 로그 행을 통째로 지어낼 수 있다(로그 위조). 그때부터 이 로그는 증적으로 쓸 수 없다.
     *
     * <p>금지 문자를 <b>빼는</b> 대신 거기서 <b>끊는</b> 이유: 빼기만 하면 뒤에 붙은 위조 문구가
     * 이어 붙어 그대로 살아남는다("1.2.3.4\n[OPS] GET /fake" → "1.2.3.4OPSGETfake"). 줄은 안
     * 쪼개지지만 읽는 사람은 여전히 속는다. IP 는 첫 이상 문자에서 끝나는 값이므로 잘라내는 편이
     * 형식에도 맞는다.
     */
    private static String sanitizeForLog(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), MAX_IP_LENGTH));
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean allowed = Character.isLetterOrDigit(c)
                    || c == '.' || c == ':' || c == '-' || c == '_' || c == '%';
            if (!allowed || safe.length() >= MAX_IP_LENGTH) {
                break;
            }
            safe.append(c);
        }
        return safe.toString();
    }
}
