package github.lms.lemuel.partner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** partner-service 공용 빈 조립. */
@Configuration
public class PartnerConfig {

    /**
     * 시각 의존 로직(기본 조회 기간, {@code capturedAt} 누락 시 대체값)의 시계.
     *
     * <p>UTC 가 아니라 <b>Asia/Seoul</b> 인 것이 중요하다. 결제 이벤트의 {@code capturedAt} 은
     * 존 없는 로컬시각이고 프로듀서는 한국 시간을 싣는다. 그 값에서 {@code sale_date} 를 뽑아
     * "8월 23일 매출" 을 만드는데, 대체값만 UTC 로 채우면 한국 시간 오전 9시 이전 결제가
     * 전날로 떨어진다 — 날짜가 하루 밀린 채로 화면에 뜨고, 아무 에러도 나지 않는다.
     * "오늘" 의 기준도 같은 이유로 한국 날짜여야 한다.
     */
    @Bean
    public Clock partnerClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
