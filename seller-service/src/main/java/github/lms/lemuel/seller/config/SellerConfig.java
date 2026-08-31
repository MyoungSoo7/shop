package github.lms.lemuel.seller.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** seller-service 공용 빈 조립. */
@Configuration
public class SellerConfig {

    /**
     * 시각 의존 로직(기본 조회 기간, {@code capturedAt} 누락 시 대체값, 신청서 제출·심사 시각)의 시계.
     *
     * <p>UTC 가 아니라 <b>Asia/Seoul</b> 인 것이 중요하다. 결제 이벤트의 {@code capturedAt} 은
     * 존 없는 로컬시각이고 프로듀서는 한국 시간을 싣는다. 그 값에서 {@code sale_date} 를 뽑는데
     * 대체값만 UTC 로 채우면 한국 시간 오전 9시 이전 결제가 전날로 떨어진다 — 셀러는 그 날짜에서
     * 출고 기한을 세므로 하루가 통째로 어긋나고, 아무 에러도 나지 않는다.
     */
    @Bean
    public Clock sellerClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
