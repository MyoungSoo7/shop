package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.operation.education.application.port.out.DeleteLecturerAssignmentPort;
import github.lms.lemuel.operation.education.application.port.out.LoadLecturerAssignmentPort;
import github.lms.lemuel.operation.education.application.port.out.SaveLecturerAssignmentPort;
import github.lms.lemuel.operation.education.domain.LecturerAssignment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 배정 영속 어댑터. 화면이 배정 목록에서 실제로 읽는 것은 <b>이름</b>이라, 조회 두 곳은 과정·강사
 * 이름을 함께 채워 돌려준다.
 *
 * <p>이름은 배정 행마다 한 번씩 찾지 않고 한 번에 모아 읽는다 — 강사 한 명이 과정 스무 개를 맡으면
 * 행마다 조회하는 방식은 조회 스물한 번이 된다(N+1).
 */
@Component
public class LecturerAssignmentPersistenceAdapter
        implements LoadLecturerAssignmentPort, SaveLecturerAssignmentPort, DeleteLecturerAssignmentPort {

    private final LecturerAssignmentRepository assignments;
    private final CourseRepository courses;
    private final LecturerRepository lecturers;

    public LecturerAssignmentPersistenceAdapter(LecturerAssignmentRepository assignments,
                                                CourseRepository courses,
                                                LecturerRepository lecturers) {
        this.assignments = assignments;
        this.courses = courses;
        this.lecturers = lecturers;
    }

    @Override
    public List<LecturerAssignment> findByLecturer(UUID lecturerId) {
        return withNames(assignments.findByLecturerIdOrderByAssignedAtAsc(lecturerId));
    }

    @Override
    public List<LecturerAssignment> findByCourse(UUID courseId) {
        return withNames(assignments.findByCourseIdOrderByAssignedAtAsc(courseId));
    }

    @Override
    public boolean exists(UUID courseId, UUID lecturerId) {
        return assignments.existsByCourseIdAndLecturerId(courseId, lecturerId);
    }

    @Override
    public LecturerAssignment save(LecturerAssignment assignment) {
        return assignments.save(LecturerAssignmentJpaEntity.fromDomain(assignment)).toDomain();
    }

    @Override
    public boolean delete(UUID courseId, UUID lecturerId) {
        return assignments.deleteAssignment(courseId, lecturerId) > 0;
    }

    private List<LecturerAssignment> withNames(List<LecturerAssignmentJpaEntity> rows) {
        if (rows.isEmpty()) return List.of();
        Set<UUID> courseIds = rows.stream().map(LecturerAssignmentJpaEntity::getCourseId).collect(Collectors.toSet());
        Set<UUID> lecturerIds = rows.stream().map(LecturerAssignmentJpaEntity::getLecturerId).collect(Collectors.toSet());
        Map<UUID, String> courseTitles = courses.findAllById(courseIds).stream()
                .collect(Collectors.toMap(CourseJpaEntity::getId, c -> c.toDomain().title()));
        Map<UUID, String> lecturerNames = lecturers.findAllById(lecturerIds).stream()
                .collect(Collectors.toMap(LecturerJpaEntity::getId, l -> l.toDomain().name()));
        return rows.stream()
                .map(LecturerAssignmentJpaEntity::toDomain)
                .map(a -> a.withNames(courseTitles.get(a.courseId()), lecturerNames.get(a.lecturerId())))
                .toList();
    }
}
