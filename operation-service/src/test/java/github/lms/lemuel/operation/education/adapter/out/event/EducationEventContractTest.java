package github.lms.lemuel.operation.education.adapter.out.event;

import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.operation.education.domain.Course;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — CoursePublished 페이로드가
 * {@code lemuel.education.course_published} 계약 스키마를 통과해야 한다.
 *
 * <p>이 토픽만 계약 스키마가 없었다. 카탈로그에는 소유 토픽으로 등재돼 있어서
 * 토픽 수(21)와 스키마 수(20)가 한 개 어긋난 채였고, 그 사실이 SPEC 과 시퀀스 문서에
 * "하나 더 많다"는 각주로 박제돼 있었다. 각주는 결함을 설명할 뿐 막지는 않는다 —
 * 이 토픽의 소비자는 아직 저장소 밖이라, 필드 이름이 바뀌어도 이 저장소 안에서는
 * 아무것도 깨지지 않는다. 계약이 유일한 경보다.
 *
 * <p>기존 {@link OutboxBackedEducationEventPublisherTest} 는 페이로드에 특정 문자열이
 * 들어 있는지를 본다. 그것은 "필드가 있다"를 보장하지만 타입·형식은 보지 않는다 —
 * publishedAt 이 epoch 숫자로 직렬화돼도 통과한다. 여기서는 스키마로 본다.
 */
class EducationEventContractTest {

    private final SaveOutboxEventPort outbox = mock(SaveOutboxEventPort.class);
    private final OutboxBackedEducationEventPublisher publisher =
            new OutboxBackedEducationEventPublisher(outbox, OutboxJson.mapper(), new TraceContextCapture());

    private String publishAndCapture(String actor) {
        when(outbox.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Course course = Course.draft(UUID.randomUUID(), "헥사고날 아키텍처 실전", "설명", "admin");
        course.publish(actor);

        publisher.coursePublished(course, actor);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        return captor.getValue().getPayload();
    }

    @Test
    @DisplayName("CoursePublished 페이로드는 lemuel.education.course_published 계약을 만족한다")
    void coursePublished_satisfiesContract() {
        EventContractValidator.assertValid("lemuel.education.course_published", publishAndCapture("admin"));
    }

    @Test
    @DisplayName("정본 샘플도 같은 계약을 만족한다 — 소비자가 이 샘플로 파싱을 검증한다")
    void canonicalSample_satisfiesContract() {
        String sample = EventContractValidator.canonicalSample("lemuel.education.course_published");
        EventContractValidator.assertValid("lemuel.education.course_published", sample);
    }

    @Test
    @DisplayName("publishedAt 이 ISO-8601 문자열이 아니면 계약 위반이다")
    void epochNumberPublishedAt_isViolation() {
        // outboxObjectMapper 대신 맨 ObjectMapper 를 쓰면 Instant 가 이렇게 나간다.
        // 이 계약이 없으면 그 회귀는 소비자 쪽에서야 드러난다 — 여기서는 소비자가 저장소 밖이다.
        Set<String> violations = EventContractValidator.validate(
                "lemuel.education.course_published",
                "{\"courseId\":\"3f6d1c02-5a41-4d8b-9f3e-2c7b0a91d4e5\",\"title\":\"x\","
                        + "\"publishedAt\":1756171260.000000000,\"publishedBy\":\"admin\",\"version\":0}");

        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("courseId 가 빠지면 계약 위반이다 — 카탈로그의 orderingKey 라 없으면 순서가 무너진다")
    void missingCourseId_isViolation() {
        Set<String> violations = EventContractValidator.validate(
                "lemuel.education.course_published",
                "{\"title\":\"x\",\"publishedAt\":\"2026-08-26T02:41:00Z\",\"version\":0}");

        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("optional 필드 추가는 위반이 아니다 (전방 호환)")
    void additiveField_isNotViolation() {
        Set<String> violations = EventContractValidator.validate(
                "lemuel.education.course_published",
                "{\"courseId\":\"3f6d1c02-5a41-4d8b-9f3e-2c7b0a91d4e5\",\"title\":\"x\","
                        + "\"publishedAt\":\"2026-08-26T02:41:00Z\",\"version\":0,\"category\":\"backend\"}");

        assertThat(violations).isEmpty();
    }
}
