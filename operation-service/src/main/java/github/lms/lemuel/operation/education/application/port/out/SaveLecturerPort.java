package github.lms.lemuel.operation.education.application.port.out;

import github.lms.lemuel.operation.education.domain.Lecturer;

/** 강사 저장 포트. 반환값은 영속 계층이 채운 값(버전 등)까지 반영된 애그리거트다. */
@FunctionalInterface
public interface SaveLecturerPort {
    Lecturer save(Lecturer lecturer);
}
