package github.lms.lemuel.operation.config;

import github.lms.lemuel.operation.anomaly.domain.AnomalyEvaluator;
import github.lms.lemuel.operation.anomaly.domain.BaselineStrategy;
import github.lms.lemuel.operation.anomaly.domain.RollingWindowBaseline;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** operation-service 공용 빈 조립. */
@Configuration
public class OperationConfig {

    /** 시각 의존 로직(refire 억제, MTTR window, 이상 탐지 스캔 시각)의 테스트 가능성을 위해 Clock 을 빈으로 주입. */
    @Bean
    public Clock operationClock() {
        return Clock.systemUTC();
    }

    /**
     * Phase 3 베이스라인 전략 — 롤링윈도우 1종만 등록(계절성은 인터페이스로 선점).
     * 순수 도메인 객체라 config(조립 계층)에서 조립한다.
     */
    @Bean
    public BaselineStrategy baselineStrategy() {
        return new RollingWindowBaseline();
    }

    /** 이상 판정 순수 함수 — 베이스라인 전략을 주입해 조립. */
    @Bean
    public AnomalyEvaluator anomalyEvaluator(BaselineStrategy baselineStrategy) {
        return new AnomalyEvaluator(baselineStrategy);
    }
}
