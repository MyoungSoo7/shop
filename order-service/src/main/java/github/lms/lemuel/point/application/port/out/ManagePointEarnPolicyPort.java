package github.lms.lemuel.point.application.port.out;

import github.lms.lemuel.point.domain.PointEarnPolicy;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 적립률 정책 쓰기 포트 — 읽기({@link PointEarnPolicyPort})와 나눈다.
 *
 * <p>{@code update} 가 없는 것이 이 포트의 계약이다. 정책은 고치는 것이 아니라 끊고 다시 만드는
 * 것이므로(ADR 0032), 행을 수정하는 손잡이 자체를 만들지 않는다.
 */
public interface ManagePointEarnPolicyPort {

    PointEarnPolicy save(PointEarnPolicy policy);

    Optional<PointEarnPolicy> findById(Long policyId);

    /**
     * 종료일을 지정한다. {@code closed_at} 에는 조작 시각을 남긴다 — 언제 누가 끊었는지의 기록이며,
     * <b>적용 여부는 여전히 날짜가 정한다</b>(종료일이 미래면 그날까지는 계속 적용된다).
     */
    Optional<PointEarnPolicy> close(Long policyId, LocalDate effectiveTo);
}
