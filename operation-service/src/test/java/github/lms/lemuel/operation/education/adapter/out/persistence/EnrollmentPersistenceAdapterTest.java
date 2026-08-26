package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.OperationServiceApplication;
import github.lms.lemuel.operation.education.application.port.out.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.out.dto.PageSpec;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.Enrollment;
import github.lms.lemuel.operation.education.domain.EnrollmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수강 신청 영속 어댑터 매핑 왕복 검증 — {@link CoursePersistenceAdapterTest} 와 같은 이유다.
 *
 * <p>{@code adapter/out/persistence} 는 커버리지 게이트 밖이라 {@code sync()} 가 필드 하나를
 * 빠뜨려도 어떤 게이트에도 안 걸리고 데이터만 조용히 상한다. 특히 <b>취소 사유</b>가 그렇다 —
 * 안 옮겨져도 화면은 "취소됨"으로 잘 보이고, 사유가 비었다는 건 분쟁이 났을 때야 드러난다.
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:enrollmentmapping;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                        + "INIT=CREATE SCHEMA IF NOT EXISTS education",
                "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
@Transactional
class EnrollmentPersistenceAdapterTest {

    @Autowired private EnrollmentPersistenceAdapter enrollments;
    @Autowired private CoursePersistenceAdapter courses;

    private UUID newCourse(String title) {
        Course course = courses.save(Course.draft(UUID.randomUUID(), title, "설명", "admin"));
        return course.id();
    }

    @Test
    @DisplayName("확정과 취소를 거친 신청이 시각·사유까지 그대로 돌아온다")
    void enrollmentSurvivesTheRoundTrip() {
        UUID courseId = newCourse("정산 교육");
        Enrollment saved = enrollments.save(
                Enrollment.apply(UUID.randomUUID(), courseId, "u-1", "김운영", "OO치과", "admin"));

        saved.confirm("admin");
        enrollments.save(saved);
        Enrollment confirmed = enrollments.findById(saved.id()).orElseThrow();
        assertThat(confirmed.status()).isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertThat(confirmed.appliedAt()).isNotNull();
        assertThat(confirmed.applicantOrganization()).isEqualTo("OO치과");

        confirmed.cancel("본인 요청", "admin");
        enrollments.save(confirmed);

        Enrollment cancelled = enrollments.findById(saved.id()).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(EnrollmentStatus.CANCELLED);
        // 사유가 안 옮겨져도 화면은 멀쩡해 보인다 — 그래서 여기서 본다.
        assertThat(cancelled.cancelReason()).isEqualTo("본인 요청");
        assertThat(cancelled.cancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("정원은 과정 왕복에서도 살아남는다 — null(무제한)과 숫자를 구분한다")
    void courseCapacitySurvivesTheRoundTrip() {
        UUID courseId = newCourse("정원 있는 교육");
        Course course = courses.findById(courseId).orElseThrow();
        assertThat(course.capacity()).isNull();

        course.changeCapacity(30, 0, "admin");
        courses.save(course);

        assertThat(courses.findById(courseId).orElseThrow().capacity()).isEqualTo(30);
    }

    @Test
    @DisplayName("과정과 상태로 좁혀 세고, 다른 과정의 신청은 세지 않는다")
    void countsAreScopedToCourseAndStatus() {
        UUID mine = newCourse("내 과정");
        UUID other = newCourse("남의 과정");
        confirmed(mine, "u-1", "김확정");
        confirmed(mine, "u-2", "이확정");
        enrollments.save(Enrollment.apply(UUID.randomUUID(), mine, "u-3", "박대기", null, "admin"));
        confirmed(other, "u-4", "최확정");

        assertThat(enrollments.countByStatus(mine, EnrollmentStatus.CONFIRMED)).isEqualTo(2L);
        assertThat(enrollments.countByStatus(mine, EnrollmentStatus.WAITING)).isEqualTo(1L);
        assertThat(enrollments.countByStatus(mine, EnrollmentStatus.CANCELLED)).isZero();
        assertThat(enrollments.countByStatus(other, EnrollmentStatus.CONFIRMED)).isEqualTo(1L);
    }

    @Test
    @DisplayName("검색은 이름과 소속 둘 다에 걸리고, 상태 필터와 함께 좁혀진다")
    void searchMatchesNameAndOrganization() {
        UUID courseId = newCourse("검색 대상 과정");
        enrollments.save(Enrollment.apply(UUID.randomUUID(), courseId, "u-1", "김운영", "서울치과", "admin"));
        enrollments.save(Enrollment.apply(UUID.randomUUID(), courseId, "u-2", "이대기", "부산치과", "admin"));
        confirmed(courseId, "u-3", "박확정");

        PageSlice<Enrollment> byName = enrollments.search(courseId, null, "김운영", new PageSpec(0, 10));
        PageSlice<Enrollment> byOrg = enrollments.search(courseId, null, "부산", new PageSpec(0, 10));
        PageSlice<Enrollment> byStatus = enrollments.search(courseId, EnrollmentStatus.CONFIRMED, "", new PageSpec(0, 10));
        PageSlice<Enrollment> all = enrollments.search(null, null, "", new PageSpec(0, 10));

        assertThat(byName.content()).extracting(Enrollment::applicantId).containsExactly("u-1");
        // 운영자는 "김OO" 로도 "OO치과" 로도 찾는다 — 한쪽만 걸리면 못 찾는 신청이 생긴다.
        assertThat(byOrg.content()).extracting(Enrollment::applicantId).containsExactly("u-2");
        assertThat(byStatus.content()).extracting(Enrollment::applicantId).containsExactly("u-3");
        assertThat(all.totalElements()).isGreaterThanOrEqualTo(3L);
    }

    @Test
    @DisplayName("목록은 접수 순서대로 나온다 — 대기 다음 차례가 이 화면의 질문이다")
    void resultsAreOrderedByApplicationTime() {
        UUID courseId = newCourse("순서 과정");
        enrollments.save(Enrollment.apply(UUID.randomUUID(), courseId, "u-1", "먼저", null, "admin"));
        enrollments.save(Enrollment.apply(UUID.randomUUID(), courseId, "u-2", "나중", null, "admin"));

        PageSlice<Enrollment> slice = enrollments.search(courseId, EnrollmentStatus.WAITING, "", new PageSpec(0, 10));

        assertThat(slice.content()).extracting(Enrollment::applicantId).containsExactly("u-1", "u-2");
    }

    private void confirmed(UUID courseId, String applicantId, String name) {
        Enrollment enrollment = enrollments.save(
                Enrollment.apply(UUID.randomUUID(), courseId, applicantId, name, null, "admin"));
        enrollment.confirm("admin");
        enrollments.save(enrollment);
    }
}
