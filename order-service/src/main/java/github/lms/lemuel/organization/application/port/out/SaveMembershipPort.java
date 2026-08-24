package github.lms.lemuel.organization.application.port.out;

import github.lms.lemuel.organization.domain.Membership;

public interface SaveMembershipPort {

    /**
     * 신규면 INSERT, 기존이면 낙관적 락 갱신.
     *
     * <p>동시 초대 경쟁으로 uq_membership_active 위반 시 {@code DataIntegrityViolationException} —
     * 호출자가 409(중복 멤버십)로 매핑한다.
     */
    Membership save(Membership membership);
}
