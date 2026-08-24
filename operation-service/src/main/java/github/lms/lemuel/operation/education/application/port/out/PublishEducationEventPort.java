package github.lms.lemuel.operation.education.application.port.out;

import github.lms.lemuel.operation.education.domain.Course;

/**
 * 과정 공개 이벤트 발행 포트.
 *
 * <p>구현체는 Transactional Outbox 에 기록하고, shared-common 폴러가
 * {@code lemuel.education.course_published}(ADR 0035 카탈로그 등재)로 발행한다.
 *
 * <p>시그니처에 JPA 엔티티가 없다 — 영속 기술은 어댑터의 몫이고, 포트는 도메인 애그리거트만 받는다.
 */
@FunctionalInterface
public interface PublishEducationEventPort {
    void coursePublished(Course course, String actor);
}
