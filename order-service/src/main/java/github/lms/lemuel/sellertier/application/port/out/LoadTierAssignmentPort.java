package github.lms.lemuel.sellertier.application.port.out;

import github.lms.lemuel.sellertier.domain.TierAssignment;

import java.util.Optional;

public interface LoadTierAssignmentPort {

    /** 없으면 empty — 처음 평가되는 셀러는 NORMAL 에서 시작한다. */
    Optional<TierAssignment> findBySellerId(Long sellerId);

    /** 프로젝션 백필용 전량 조회 — 소비측에 이미 확정된 등급을 재발행하기 위한 것이다. */
    java.util.List<TierAssignment> findAll();
}
