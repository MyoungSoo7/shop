package github.lms.lemuel.operation.education.application.port.out;

import github.lms.lemuel.operation.education.application.port.out.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.out.dto.PageSpec;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.CourseStatus;

import java.util.Optional;
import java.util.UUID;

/** 과정 조회 포트 — 저장 의도({@link SaveCoursePort})와 분리한다(ISP). */
public interface LoadCoursePort {

    Optional<Course> findById(UUID id);

    /**
     * 제목 부분일치로 과정을 찾는다. {@code status} 가 null 이면 상태를 가리지 않는다.
     *
     * <p>시그니처에 {@code Pageable} 도 {@code Page} 도 없다 — 페이지네이션 기술은 어댑터의 몫이다.
     */
    PageSlice<Course> search(CourseStatus status, String titleKeyword, PageSpec page);
}
