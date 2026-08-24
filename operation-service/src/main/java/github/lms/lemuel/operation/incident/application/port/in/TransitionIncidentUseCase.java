package github.lms.lemuel.operation.incident.application.port.in;

import github.lms.lemuel.operation.incident.domain.Incident;

/**
 * 운영자 인시던트 전이 유스케이스 (ack / resolve / false-positive).
 *
 * <p>전이 불가(터미널 재전이 등)는 {@code InvalidIncidentTransitionException},
 * 낙관적 락 충돌은 {@code OptimisticLockingFailureException} — 웹 계층이 둘 다 409 로 매핑한다.
 */
public interface TransitionIncidentUseCase {

    Incident acknowledge(Long incidentId, String actor, String note);

    Incident resolve(Long incidentId, String actor, String note);

    Incident markFalsePositive(Long incidentId, String actor, String note);
}
