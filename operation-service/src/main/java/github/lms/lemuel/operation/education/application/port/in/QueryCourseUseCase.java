package github.lms.lemuel.operation.education.application.port.in;

import github.lms.lemuel.operation.education.application.port.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.dto.PageSpec;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.CourseStatus;
import github.lms.lemuel.operation.education.domain.exception.CourseNotFoundException;

import java.util.UUID;

/**
 * 과정 조회 창구.
 *
 * <p>{@link PageSlice}·{@link PageSpec} 은 스프링 데이터의 {@code Page}·{@code Pageable} 을 대신하는
 * 자체 타입이다. 포트가 {@code Pageable} 로 말하면 "우리 저장소는 스프링 데이터다" 가 유스케이스
 * 시그니처에 박히고, 그 타입을 만들려고 어댑터가 아닌 곳까지 스프링 데이터를 임포트하게 된다.
 */
public interface QueryCourseUseCase {

    /** @param query 빈 문자열이면 제목 필터 없음. null 도 같게 다룬다. */
    PageSlice<Course> list(CourseStatus status, String query, PageSpec page);

    /** @throws CourseNotFoundException 해당 id 의 과정이 없을 때 */
    Course get(UUID id);
}
