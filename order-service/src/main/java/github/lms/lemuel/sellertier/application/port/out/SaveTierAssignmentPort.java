package github.lms.lemuel.sellertier.application.port.out;

import github.lms.lemuel.sellertier.domain.TierAssignment;

public interface SaveTierAssignmentPort {

    /** 등급 정본 저장 + users.seller_tier 읽기 캐시 동기화. */
    TierAssignment save(TierAssignment assignment);
}
