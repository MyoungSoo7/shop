package github.lms.lemuel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 시간 소스 구성 — 커머스 도메인의 "지금" 판정을 한 곳으로 모은다.
 *
 * <p>쿠폰 사용기간·비밀번호 재설정 토큰 만료의 경계는 <b>한국 영업일(KST)</b> 기준이다. JVM 기본
 * 타임존이 UTC 인 컨테이너에서 {@code LocalDateTime.now()}(zone 미지정)로 판정하면 KST 자정~09시
 * 사이에 만료 경계가 하루 어긋나, 만료된 쿠폰이 통과하거나 유효한 쿠폰이 거절될 수 있다. 이를 막기
 * 위해 응용 서비스 계층은 정적 {@code now()} 대신 이 {@link Clock} 빈을 주입받아
 * {@code LocalDateTime.now(clock)} 로 KST 기준 시각을 얻어 도메인에 넘긴다.
 *
 * <p>테스트에서는 고정 {@link Clock#fixed}로 대체해 만료 경계를 결정적으로 검증한다.
 *
 * <p>settlement-service 의 동명 설정과 같은 규약이다 — 서비스별 DB 처럼 시간 기준도 서비스마다
 * 자기 것을 소유하되, 업무 표준시(KST)는 동일하다.
 */
@Configuration
public class TimeConfig {

    /** 커머스 도메인의 업무 표준시. 스케줄러 cron zone("Asia/Seoul")과 동일 출처. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
