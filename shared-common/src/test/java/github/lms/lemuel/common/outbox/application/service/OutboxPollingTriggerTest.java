package github.lms.lemuel.common.outbox.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 폴링 트리거의 게이팅 규약.
 *
 * <p>발행 로직(@link OutboxPublisherScheduler})과 <b>주기 실행</b>을 분리한 이유: 테스트에서 폴링만
 * 끄고 싶은데, 발행기 빈까지 사라지면 수동 호출로 발행을 검증하는 테스트
 * (order-service {@code KafkaOutboxIntegrationTest})가 빈 주입에 실패한다.
 */
class OutboxPollingTriggerTest {

    private final OutboxPublisherScheduler scheduler = mock(OutboxPublisherScheduler.class);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(OutboxPublisherScheduler.class, () -> scheduler)
                .withUserConfiguration(OutboxPollingTrigger.class);
    }

    @Test
    @DisplayName("속성이 없으면 폴링 트리거가 뜬다 — 운영 기본은 폴링 ON")
    void 속성_없으면_폴링_ON() {
        runner().run(ctx -> assertThat(ctx).hasSingleBean(OutboxPollingTrigger.class));
    }

    @Test
    @DisplayName("app.outbox.polling.enabled=false 면 트리거만 빠지고 발행기 빈은 남는다")
    void 폴링_OFF_여도_발행기_빈은_남는다() {
        runner().withPropertyValues("app.outbox.polling.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(OutboxPollingTrigger.class);
                    assertThat(ctx).hasSingleBean(OutboxPublisherScheduler.class);
                });
    }

    @Test
    @DisplayName("트리거는 발행기에 위임한다")
    void 트리거는_발행기에_위임한다() {
        new OutboxPollingTrigger(scheduler).poll();

        verify(scheduler).publishPendingEvents();
    }

    /**
     * 게이팅 우회 방지. {@code @Scheduled} 가 발행기 쪽으로 다시 옮겨 붙으면 조건부 빈을 꺼도
     * 주기 실행이 살아남는다 — 그 회귀를 여기서 잡는다.
     */
    @Test
    @DisplayName("@Scheduled 는 트리거에만 붙는다")
    void Scheduled_는_트리거에만_붙는다() throws Exception {
        Method triggerPoll = OutboxPollingTrigger.class.getDeclaredMethod("poll");
        Method publish = OutboxPublisherScheduler.class.getDeclaredMethod("publishPendingEvents");

        assertThat(triggerPoll.isAnnotationPresent(Scheduled.class)).isTrue();
        assertThat(publish.isAnnotationPresent(Scheduled.class)).isFalse();
    }
}
