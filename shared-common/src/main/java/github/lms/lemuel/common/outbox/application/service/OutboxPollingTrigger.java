package github.lms.lemuel.common.outbox.application.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 폴링의 <b>주기 실행</b>만 담당하는 트리거.
 *
 * <p>발행 로직({@link OutboxPublisherScheduler})과 분리한 이유는 <b>끌 수 있는 단위를 쪼개기</b>
 * 위해서다. 테스트에서 이 폴러는 2초마다 DB 를 두드리는데, 컨텍스트가 내려갈 때 Hikari 셧다운과
 * 경합해 매 실행 수십 줄의 WARN/ERROR 를 남겼다(실측: run 31676479927 의 card-service 구간
 * {@code lemuel-card-pool - Failed to validate connection ... This connection has been closed} 42줄).
 * 빌드를 깨지는 않지만 진짜 DB 문제를 이 노이즈가 덮는다.
 *
 * <p>그렇다고 {@link OutboxPublisherScheduler} 빈 자체를 조건부로 만들면, 폴링을 끈 채 수동 호출로
 * 발행을 검증하는 테스트(order-service {@code KafkaOutboxIntegrationTest})가 주입에 실패한다.
 * 그래서 <b>주기 실행만</b> 조건부로 뗀다.
 *
 * <p><b>기본값은 ON</b>({@code matchIfMissing = true}) — 운영에서 설정을 빠뜨렸을 때 발행이 조용히
 * 멈추는 쪽이 훨씬 위험하다(outbox 적체 = 이벤트 미발행). 끄는 것은 테스트가 명시할 때뿐이다
 * (부모 {@code build.gradle.kts} 의 test JVM 시스템 프로퍼티).
 */
@Component
@ConditionalOnProperty(name = "app.outbox.polling.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPollingTrigger {

    private final OutboxPublisherScheduler scheduler;

    public OutboxPollingTrigger(OutboxPublisherScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${app.outbox.polling-delay-ms:2000}")
    public void poll() {
        scheduler.publishPendingEvents();
    }
}
