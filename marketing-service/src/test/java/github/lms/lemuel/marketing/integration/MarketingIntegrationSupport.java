package github.lms.lemuel.marketing.integration;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 마케팅 통합 테스트가 공유하는 것들.
 *
 * <p>여기 모은 이유는 두 가지다.
 *
 * <ul>
 *   <li><b>날짜</b> — 프로덕션 경로는 {@code MarketingClock.today()}(KST)로만 "오늘"을 정한다.
 *       테스트가 {@code LocalDate.now()} 를 쓰면 UTC 로 도는 CI 러너에서 한국 시간 자정~오전 9시
 *       구간에 하루가 어긋나고, 캠페인 기간을 하루짜리로 잡은 테스트가 그 시간대에만 빨개진다.
 *       테스트도 같은 시계를 봐야 한다.</li>
 *   <li><b>주체</b> — 참여자·운영자는 JWT 에서만 나온다({@code CurrentMember}). 요청 본문에
 *       회원번호가 없다는 것 자체가 검증 대상이므로, 인증은 principal 을 심는 방식으로만 만든다.</li>
 * </ul>
 */
final class MarketingIntegrationSupport {

    /** 프로덕션과 같은 시계. {@code MarketingClock} 은 package-private 이라 여기서 같은 정의를 쓴다. */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private MarketingIntegrationSupport() {
    }

    static LocalDate today() {
        return LocalDate.now(KST);
    }

    /** Docker 없는 환경(로컬 노트북 등)에서는 통합 테스트를 건너뛴다. */
    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Exception | LinkageError ex) {
            return false;
        }
    }

    static RequestPostProcessor member(long userId) {
        return principal(userId, "u" + userId + "@lemuel.test", "USER");
    }

    static RequestPostProcessor admin() {
        return principal(9001L, "admin@lemuel.test", "ADMIN");
    }

    private static RequestPostProcessor principal(Long userId, String email, String role) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(userId, email, role),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    /** 프로모션 테이블 전부 비우기. FK 순서를 신경 쓰지 않도록 한 문장으로 자른다. */
    static String truncateAll() {
        return """
                TRUNCATE TABLE marketing.outbox_events,
                               marketing.processed_events,
                               marketing.reward_grants,
                               marketing.attendance_achievements,
                               marketing.attendance_records,
                               marketing.attendance_campaigns,
                               marketing.luckybox_draws,
                               marketing.luckybox_prizes,
                               marketing.luckybox_campaigns
                RESTART IDENTITY CASCADE
                """;
    }
}
