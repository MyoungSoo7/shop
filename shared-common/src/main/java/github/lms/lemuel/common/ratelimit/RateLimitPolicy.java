package github.lms.lemuel.common.ratelimit;

import java.time.Duration;
import java.util.function.Function;

/**
 * 경로별 Rate Limit 정책.
 *
 * @param name         로깅·메트릭 식별자
 * @param pathPrefix   매칭 경로 (startsWith)
 * @param keyExtractor 버킷 키 추출 (IP 기준 / actor 기준 등)
 * @param capacity     한 윈도우 내 허용 요청 수
 * @param window       윈도우 길이
 */
public record RateLimitPolicy(
        String name,
        String pathPrefix,
        Function<RateLimitKeySource, String> keyExtractor,
        long capacity,
        Duration window
) {
    public boolean matches(String path) {
        return path != null && path.startsWith(pathPrefix);
    }

    /** IP 기준 키: "ip:1.2.3.4". */
    public static Function<RateLimitKeySource, String> byIp() {
        return s -> "ip:" + s.ipAddress();
    }

    /** 인증 유저 이메일 기준 키, 미인증은 IP 로 fallback. */
    public static Function<RateLimitKeySource, String> byActorOrIp() {
        return s -> s.actorEmail() != null && !s.actorEmail().isBlank()
                ? "actor:" + s.actorEmail()
                : "ip:" + s.ipAddress();
    }
}
