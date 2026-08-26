package github.lms.lemuel.operation.education.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 강사–과정 배정. 상태가 없는 연결이라 애그리거트가 아니라 값이다 — 만들거나 없애거나 둘뿐이고
 * 중간 상태가 없다.
 *
 * <p>{@code courseTitle}·{@code lecturerName} 은 조회 결과를 표시할 때만 채워진다. 저장할 때는
 * null 이다 — 이름은 각 애그리거트가 소유하고 여기 적힌 값은 화면용 사본일 뿐이라, 이 값을
 * 근거로 판단하면 강사가 개명한 뒤에도 옛 이름으로 판단하게 된다.
 */
public record LecturerAssignment(UUID id, UUID courseId, UUID lecturerId, Instant assignedAt,
                                 String assignedBy, String courseTitle, String lecturerName) {

    public static LecturerAssignment assign(UUID id, UUID courseId, UUID lecturerId, String actor) {
        if (courseId == null) throw new IllegalArgumentException("courseId is required");
        if (lecturerId == null) throw new IllegalArgumentException("lecturerId is required");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor is required");
        return new LecturerAssignment(id, courseId, lecturerId, Instant.now(), actor, null, null);
    }

    /** 화면용 이름을 채운 사본. 조회 어댑터가 조인 결과를 실어 나를 때만 쓴다. */
    public LecturerAssignment withNames(String courseTitle, String lecturerName) {
        return new LecturerAssignment(id, courseId, lecturerId, assignedAt, assignedBy,
                courseTitle, lecturerName);
    }
}
