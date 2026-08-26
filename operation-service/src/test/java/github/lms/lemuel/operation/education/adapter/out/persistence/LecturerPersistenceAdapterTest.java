package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.OperationServiceApplication;
import github.lms.lemuel.operation.education.application.port.out.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.out.dto.PageSpec;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.Lecturer;
import github.lms.lemuel.operation.education.domain.LecturerAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 강사 영속 어댑터 매핑 왕복 검증.
 *
 * <p>여기서 실제로 위험한 것은 <b>분야 컬렉션</b>이다. 두 축(전공·강의 분야)이 한 테이블에 kind 로
 * 들어가므로, 수정할 때 한 축만 갈아 끼운다는 게 다른 축의 행까지 지우는 것으로 나타날 수 있다.
 * 그렇게 되면 화면에서는 "전공을 바꿨더니 강의 분야가 사라졌다"로 보이고, 어떤 게이트도 잡지 않는다
 * ({@code adapter/out/persistence} 는 커버리지 게이트 밖이다).
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:lecturermapping;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                        + "INIT=CREATE SCHEMA IF NOT EXISTS education",
                "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
@Transactional
class LecturerPersistenceAdapterTest {

    @Autowired private LecturerPersistenceAdapter lecturers;
    @Autowired private LecturerAssignmentPersistenceAdapter assignments;
    @Autowired private CoursePersistenceAdapter courses;

    private static Set<String> ordered(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    private Lecturer newLecturer(String name, String office, Set<String> majors, Set<String> lectureFields) {
        return lecturers.save(Lecturer.register(UUID.randomUUID(), name, "Kim", "OO대학원", office,
                "10년", "외부 강사", "약력", "history", "메모", majors, lectureFields, "admin"));
    }

    private UUID newCourse(String title) {
        return courses.save(Course.draft(UUID.randomUUID(), title, "설명", "admin")).id();
    }

    @Test
    @DisplayName("모든 칸이 그대로 돌아온다 — 이력·메모처럼 화면 아래쪽에 있는 값이 특히 조용히 샌다")
    void lecturerSurvivesTheRoundTrip() {
        Lecturer saved = newLecturer("김강사", "OO치과", ordered("보철", "임플란트"), ordered("보철 실습"));

        Lecturer found = lecturers.findById(saved.id()).orElseThrow();

        assertThat(found.name()).isEqualTo("김강사");
        assertThat(found.englishName()).isEqualTo("Kim");
        assertThat(found.graduateSchool()).isEqualTo("OO대학원");
        assertThat(found.officeName()).isEqualTo("OO치과");
        assertThat(found.career()).isEqualTo("10년");
        assertThat(found.lecturerType()).isEqualTo("외부 강사");
        assertThat(found.historyKo()).isEqualTo("약력");
        assertThat(found.historyEn()).isEqualTo("history");
        assertThat(found.etcMemo()).isEqualTo("메모");
        assertThat(found.majors()).containsExactlyInAnyOrder("보철", "임플란트");
        assertThat(found.lectureFields()).containsExactly("보철 실습");
        assertThat(found.active()).isTrue();
        assertThat(found.deleted()).isFalse();
    }

    @Test
    @DisplayName("전공만 바꿔도 강의 분야는 살아 있다 — 두 축이 한 테이블을 공유하기 때문에 이게 진짜 위험이다")
    void changingOneAxisKeepsTheOther() {
        Lecturer saved = newLecturer("김강사", "OO치과", ordered("보철"), ordered("보철 실습", "교정 실습"));

        Lecturer loaded = lecturers.findById(saved.id()).orElseThrow();
        loaded.update("김강사", "Kim", "OO대학원", "OO치과", "10년", "외부 강사", "약력", "history", "메모",
                ordered("교정"), ordered("보철 실습", "교정 실습"), "admin");
        lecturers.save(loaded);

        Lecturer found = lecturers.findById(saved.id()).orElseThrow();
        assertThat(found.majors()).containsExactly("교정");
        assertThat(found.lectureFields()).containsExactlyInAnyOrder("보철 실습", "교정 실습");
    }

    @Test
    @DisplayName("분야를 비우면 컬렉션 행도 사라진다 — 남으면 지운 전공이 목록에 계속 뜬다")
    void clearingFieldsRemovesRows() {
        Lecturer saved = newLecturer("김강사", "OO치과", ordered("보철"), ordered("보철 실습"));

        Lecturer loaded = lecturers.findById(saved.id()).orElseThrow();
        loaded.update("김강사", null, null, null, null, null, null, null, null,
                Set.of(), Set.of(), "admin");
        lecturers.save(loaded);

        Lecturer found = lecturers.findById(saved.id()).orElseThrow();
        assertThat(found.majors()).isEmpty();
        assertThat(found.lectureFields()).isEmpty();
    }

    @Test
    @DisplayName("삭제한 강사는 목록 조회에서 빠진다 — 지운 사람이 배정 후보로 돌아오면 안 된다")
    void deletedIsExcludedFromSearch() {
        Lecturer kept = newLecturer("김강사", "OO치과", ordered("보철"), Set.of());
        Lecturer removed = newLecturer("이강사", "OO치과", ordered("교정"), Set.of());

        Lecturer loaded = lecturers.findById(removed.id()).orElseThrow();
        loaded.delete("admin");
        lecturers.save(loaded);

        PageSlice<Lecturer> page = lecturers.search("", false, new PageSpec(0, 20));
        assertThat(page.content()).extracting(Lecturer::id).containsExactly(kept.id());
        assertThat(page.totalElements()).isEqualTo(1);
        // 지웠어도 id 로는 여전히 읽힌다 — 지난 배정 기록이 가리키는 대상이라서다.
        assertThat(lecturers.findById(removed.id())).isPresent();
    }

    @Test
    @DisplayName("activeOnly 는 비활성만 걸러 낸다 — 삭제와 다른 축이다")
    void activeOnlyFiltersInactive() {
        Lecturer active = newLecturer("김강사", "OO치과", Set.of(), Set.of());
        Lecturer resting = newLecturer("이강사", "OO치과", Set.of(), Set.of());

        Lecturer loaded = lecturers.findById(resting.id()).orElseThrow();
        loaded.deactivate("admin");
        lecturers.save(loaded);

        assertThat(lecturers.search("", true, new PageSpec(0, 20)).content())
                .extracting(Lecturer::id).containsExactly(active.id());
        assertThat(lecturers.search("", false, new PageSpec(0, 20)).content())
                .extracting(Lecturer::id).containsExactlyInAnyOrder(active.id(), resting.id());
    }

    @Test
    @DisplayName("검색어는 이름과 소속 둘 다에 걸린다")
    void searchMatchesNameAndOffice() {
        Lecturer kim = newLecturer("김강사", "행복치과", Set.of(), Set.of());
        Lecturer lee = newLecturer("이강사", "OO치과", Set.of(), Set.of());

        assertThat(lecturers.search("김", false, new PageSpec(0, 20)).content())
                .extracting(Lecturer::id).containsExactly(kim.id());
        assertThat(lecturers.search("행복", false, new PageSpec(0, 20)).content())
                .extracting(Lecturer::id).containsExactly(kim.id());
        assertThat(lecturers.search("치과", false, new PageSpec(0, 20)).content())
                .extracting(Lecturer::id).containsExactlyInAnyOrder(kim.id(), lee.id());
    }

    @Test
    @DisplayName("배정 조회는 과정·강사 이름을 함께 채워 준다 — 화면이 id 를 보여 줄 수는 없다")
    void assignmentsCarryNames() {
        Lecturer lecturer = newLecturer("김강사", "OO치과", Set.of(), Set.of());
        UUID courseId = newCourse("정산 교육");

        assignments.save(LecturerAssignment.assign(UUID.randomUUID(), courseId, lecturer.id(), "admin"));

        List<LecturerAssignment> byLecturer = assignments.findByLecturer(lecturer.id());
        assertThat(byLecturer).hasSize(1);
        assertThat(byLecturer.get(0).courseTitle()).isEqualTo("정산 교육");
        assertThat(byLecturer.get(0).lecturerName()).isEqualTo("김강사");
        assertThat(byLecturer.get(0).assignedBy()).isEqualTo("admin");

        assertThat(assignments.findByCourse(courseId)).hasSize(1);
        assertThat(assignments.exists(courseId, lecturer.id())).isTrue();
    }

    @Test
    @DisplayName("해제는 지웠을 때만 true 다")
    void deleteReportsWhetherItRemovedAnything() {
        Lecturer lecturer = newLecturer("김강사", "OO치과", Set.of(), Set.of());
        UUID courseId = newCourse("정산 교육");
        assignments.save(LecturerAssignment.assign(UUID.randomUUID(), courseId, lecturer.id(), "admin"));

        assertThat(assignments.delete(courseId, lecturer.id())).isTrue();
        assertThat(assignments.delete(courseId, lecturer.id())).isFalse();
        assertThat(assignments.exists(courseId, lecturer.id())).isFalse();
    }

    @Test
    @DisplayName("배정이 없으면 빈 목록이다 — 이름 조회를 건너뛴다")
    void emptyAssignmentsShortCircuit() {
        Lecturer lecturer = newLecturer("김강사", "OO치과", Set.of(), Set.of());

        assertThat(assignments.findByLecturer(lecturer.id())).isEmpty();
    }
}
