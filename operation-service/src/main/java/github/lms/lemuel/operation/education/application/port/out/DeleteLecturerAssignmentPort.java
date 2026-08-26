package github.lms.lemuel.operation.education.application.port.out;

import java.util.UUID;

/**
 * 배정 해제 포트. 배정은 소프트 삭제하지 않는다 — 강사 명부와 달리 배정은 "지금 누가 맡고
 * 있는가"만 뜻하고, 지난 배정의 흔적은 감사 로그가 남긴다.
 */
@FunctionalInterface
public interface DeleteLecturerAssignmentPort {
    /** 실제로 지웠으면 true. 없던 배정을 해제하려 한 것과 구분하기 위해 결과를 돌려준다. */
    boolean delete(UUID courseId, UUID lecturerId);
}
