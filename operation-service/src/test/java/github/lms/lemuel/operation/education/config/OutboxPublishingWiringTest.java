package github.lms.lemuel.operation.education.config;

import github.lms.lemuel.common.outbox.adapter.out.event.ApplicationEventOutboxPublisher;
import github.lms.lemuel.common.outbox.application.port.out.PublishDlqEventPort;
import github.lms.lemuel.common.outbox.application.port.out.PublishExternalEventPort;
import github.lms.lemuel.common.outbox.application.service.OutboxBatchEventPublisher;
import github.lms.lemuel.common.outbox.application.service.OutboxPollingTrigger;
import github.lms.lemuel.common.outbox.application.service.OutboxPublisherScheduler;
import github.lms.lemuel.OperationServiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.Task;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 발행 배선 회귀 테스트.
 *
 * <p><b>무엇을 막는가</b> — 2026-08-22 이전 education 은 발행 어댑터만 있고 폴러가 없었다.
 * {@code education.outbox_events} 에 PENDING 행이 쌓이는데 그 행을 집어 갈 주체가 없었고,
 * 컴파일도 테스트도 API 응답도 전부 정상이라 증상이 어디에도 나타나지 않았다.
 * 기존 컨텍스트 로드 테스트는 이 상태에서도 통과했다 — 없는 빈은 아무 소리도 내지 않기 때문이다.
 *
 * <p><b>왜 두 가지를 따로 단언하는가</b> — 깨질 수 있는 지점이 둘이다.
 * <ol>
 *   <li>빈이 컨텍스트에 없다(제한 스캔 + {@code @Import} 누락)</li>
 *   <li>빈은 있는데 {@code @EnableScheduling} 이 없어 <b>등록만 되고 영영 돌지 않는다</b></li>
 * </ol>
 * ②는 빈 존재 검사만으로는 통과한다. 그래서 스케줄 태스크가 실제로 등록됐는지까지 본다.
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.datasource.url=jdbc:h2:mem:education-outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                // 부모 build.gradle.kts:99 가 전 테스트에서 app.outbox.polling.enabled=false 로 폴링을
                // 끈다(컨텍스트 종료 시 Hikari 셧다운과 경합해 WARN 을 쏟기 때문). 이 테스트는 바로 그
                // 주기 실행이 켜지는지를 보는 테스트라 명시적으로 되켠다.
                "app.outbox.polling.enabled=true",
                // 대신 주기를 1시간으로 늘려 실제 발행은 돌지 않게 한다 — 등록 여부만 본다.
                "app.outbox.polling-delay-ms=3600000"
        })
class OutboxPublishingWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Outbox 발행 빈이 컨텍스트에 있다 — 제한 스캔이라 @Import 가 유일한 경로다")
    void publishingBeansArePresent() {
        assertThat(context.getBeansOfType(OutboxPublisherScheduler.class)).hasSize(1);
        assertThat(context.getBeansOfType(OutboxBatchEventPublisher.class)).hasSize(1);
        assertThat(context.getBeansOfType(OutboxPollingTrigger.class)).hasSize(1);
    }

    @Test
    @DisplayName("app.kafka.enabled=false(기본) 면 in-process 폴백이 발행 포트를 채운다 — 브로커 없이 부팅한다")
    void fallbackPublisherIsWiredWhenKafkaDisabled() {
        assertThat(context.getBean(PublishExternalEventPort.class))
                .isInstanceOf(ApplicationEventOutboxPublisher.class);
        assertThat(context.getBeansOfType(PublishDlqEventPort.class)).hasSize(1);
    }

    @Test
    @DisplayName("폴러의 @Scheduled 가 실제로 등록됐다 — @EnableScheduling 이 빠지면 빈만 남고 돌지 않는다")
    void pollingTaskIsActuallyScheduled() {
        ScheduledAnnotationBeanPostProcessor processor =
                context.getBean(ScheduledAnnotationBeanPostProcessor.class);
        Set<ScheduledTask> tasks = processor.getScheduledTasks();

        assertThat(tasks)
                .as("등록된 스케줄 태스크 중 Outbox 폴러가 있어야 한다 (실제: %s)", describe(tasks))
                .anySatisfy(task -> assertThat(task.getTask().toString())
                        .contains(OutboxPollingTrigger.class.getSimpleName()));
    }

    private static String describe(Set<ScheduledTask> tasks) {
        return tasks.stream().map(ScheduledTask::getTask).map(Task::toString).toList().toString();
    }
}
