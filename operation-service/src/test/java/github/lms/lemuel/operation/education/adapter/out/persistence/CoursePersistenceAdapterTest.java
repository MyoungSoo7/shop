package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.OperationServiceApplication;
import github.lms.lemuel.operation.education.application.port.out.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.out.dto.PageSpec;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.CourseStatus;
import github.lms.lemuel.operation.education.domain.Lesson;
import github.lms.lemuel.operation.education.domain.LessonContentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영속 어댑터 매핑 왕복 검증.
 *
 * <p>{@code adapter/out/persistence} 는 커버리지 게이트 측정 범위 밖이라, 매핑 누락(예: sync() 가
 * 어떤 필드를 안 옮김)은 어떤 게이트에도 걸리지 않고 데이터만 조용히 상한다. 실제 DB 로 한 번
 * 왕복시켜 도메인 상태가 그대로 돌아오는지 본다.
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:educationmapping;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                        + "INIT=CREATE SCHEMA IF NOT EXISTS education",
                // board 슬라이스 엔티티도 schema = "board" 로 명시 매핑이라 create-drop 이 두 스키마를
                // 요구한다 — URL INIT 의 \; 이스케이프는 인라인 프로퍼티에서 깨지므로 Hibernate 가
                // 네임스페이스(스키마)를 직접 만들게 한다.
                "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
@Transactional
class CoursePersistenceAdapterTest {

    @Autowired private CoursePersistenceAdapter courses;
    @Autowired private LessonPersistenceAdapter lessons;

    @Test
    void courseSurvivesTheRoundTripIncludingTransitionState() {
        Course draft = Course.draft(UUID.randomUUID(), "정산 교육", "설명", "admin");
        draft.publish("publisher");

        courses.save(draft);
        Course reloaded = courses.findById(draft.id()).orElseThrow();

        assertThat(reloaded.id()).isEqualTo(draft.id());
        assertThat(reloaded.title()).isEqualTo("정산 교육");
        assertThat(reloaded.description()).isEqualTo("설명");
        assertThat(reloaded.status()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(reloaded.publishedAt()).isEqualTo(draft.publishedAt());
        assertThat(reloaded.updatedBy()).isEqualTo("publisher");
    }

    @Test
    void savingAnExistingCourseUpdatesItInsteadOfInserting() {
        Course course = Course.draft(UUID.randomUUID(), "처음 제목", "설명", "admin");
        courses.save(course);

        course.update("바뀐 제목", "바뀐 설명", "editor");
        courses.save(course);

        Course reloaded = courses.findById(course.id()).orElseThrow();
        assertThat(reloaded.title()).isEqualTo("바뀐 제목");
        assertThat(reloaded.description()).isEqualTo("바뀐 설명");
        assertThat(reloaded.updatedBy()).isEqualTo("editor");
        assertThat(courses.search(null, "", new PageSpec(0, 20)).totalElements()).isEqualTo(1);
    }

    @Test
    void searchFiltersByStatusAndTitle() {
        Course published = Course.draft(UUID.randomUUID(), "정산 교육", null, "admin");
        published.publish("admin");
        courses.save(published);
        courses.save(Course.draft(UUID.randomUUID(), "회계 교육", null, "admin"));

        PageSlice<Course> byTitle = courses.search(null, "정산", new PageSpec(0, 20));
        PageSlice<Course> byStatus = courses.search(CourseStatus.DRAFT, "", new PageSpec(0, 20));

        assertThat(byTitle.content()).extracting(Course::title).containsExactly("정산 교육");
        assertThat(byStatus.content()).extracting(Course::title).containsExactly("회계 교육");
        assertThat(byTitle.page()).isZero();
        assertThat(byTitle.size()).isEqualTo(20);
    }

    @Test
    void lessonSurvivesTheRoundTripAndKeepsCourseOrdering() {
        UUID courseId = UUID.randomUUID();
        lessons.save(Lesson.create(UUID.randomUUID(), courseId, "둘째 차시", "설명2", 2, "DOCUMENT", "d1", false, "admin"));
        lessons.save(Lesson.create(UUID.randomUUID(), courseId, "첫 차시", "설명1", 1, "VIDEO", "v1", true, "admin"));

        List<Lesson> found = lessons.findByCourseOrderedBySequence(courseId);

        assertThat(found).extracting(Lesson::title).containsExactly("첫 차시", "둘째 차시");
        Lesson first = found.get(0);
        assertThat(first.contentType()).isEqualTo(LessonContentType.VIDEO);
        assertThat(first.contentRef()).isEqualTo("v1");
        assertThat(first.required()).isTrue();
        assertThat(first.description()).isEqualTo("설명1");
        assertThat(lessons.findById(first.id())).isPresent();
    }

    @Test
    void deletedLessonIsGone() {
        UUID courseId = UUID.randomUUID();
        Lesson lesson = lessons.save(Lesson.create(UUID.randomUUID(), courseId, "차시", null, 1, "VIDEO", "v1", true, "admin"));

        lessons.deleteById(lesson.id());

        assertThat(lessons.findById(lesson.id())).isEmpty();
    }
}
