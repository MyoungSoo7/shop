package github.lms.lemuel.operation.education.adapter.out.event;

import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.operation.education.domain.Course;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 발행 경로 검증 — 이 어댑터는 커버리지 게이트 측정 범위 밖({@code adapter/out/event} 제외)이라
 * 테스트가 없으면 검증이 0이 된다. 페이로드 키는 소비자 계약이므로 직렬화 결과까지 본다.
 */
class OutboxBackedEducationEventPublisherTest {

    private final SaveOutboxEventPort outbox = mock(SaveOutboxEventPort.class);
    private final OutboxBackedEducationEventPublisher publisher =
            new OutboxBackedEducationEventPublisher(outbox, OutboxJson.mapper(), new TraceContextCapture());

    @Test
    void publishedCourseIsRecordedAsAnOutboxEventWithTheAgreedPayload() {
        when(outbox.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");
        course.publish("admin");

        publisher.coursePublished(course, "admin");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        OutboxEvent event = captor.getValue();

        // 토픽은 aggregateType+eventType 에서 파생된다 — lemuel.education.course_published (ADR 0035).
        assertThat(event.getAggregateType()).isEqualTo("Education");
        assertThat(event.getEventType()).isEqualTo("CoursePublished");
        assertThat(event.getAggregateId()).isEqualTo(course.id().toString());
        assertThat(event.getPayload())
                .contains("\"courseId\"")
                .contains(course.id().toString())
                .contains("\"title\"")
                .contains("정산 교육")
                .contains("\"publishedBy\"")
                .contains("admin")
                .contains("\"version\"")
                .contains("\"publishedAt\"");
    }

    @Test
    void publishedAtSurvivesSerialization() {
        when(outbox.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Course course = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");
        course.publish("admin");

        publisher.coursePublished(course, "admin");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        // Instant 는 모듈 등록 여부에 따라 표현이 갈린다 — null 로 새어 나가지 않는 것이 계약이다.
        assertThat(captor.getValue().getPayload()).doesNotContain("\"publishedAt\":null");
    }
}
