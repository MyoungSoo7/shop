package github.lms.lemuel.operation.education.application.port.out;

import github.lms.lemuel.operation.education.application.port.out.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.out.dto.PageSpec;
import github.lms.lemuel.operation.education.domain.Lecturer;

import java.util.Optional;
import java.util.UUID;

/** 강사 조회 포트 — 저장 의도({@link SaveLecturerPort})와 분리한다(ISP). */
public interface LoadLecturerPort {

    Optional<Lecturer> findById(UUID id);

    /**
     * 명부를 찾는다. {@code keyword} 는 이름·소속 부분일치이고, {@code activeOnly} 가 true 면
     * 쉬는 강사를 뺀다.
     *
     * <p>지운 강사(deleted)는 어떤 조합에서도 나오지 않는다 — 명부에서 뺀 사람을 목록에 다시
     * 띄우면 배정 버튼이 함께 살아나고, 그 배정은 도메인이 거절해 눌러도 아무 일이 안 일어난다.
     */
    PageSlice<Lecturer> search(String keyword, boolean activeOnly, PageSpec page);
}
