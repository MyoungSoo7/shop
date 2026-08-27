package github.lms.lemuel.marketing.application.port.out;

import github.lms.lemuel.marketing.domain.RewardGrant;
import github.lms.lemuel.marketing.domain.RewardSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 보상 요청 적재·저장. */
public interface RewardGrantPort {

    Optional<RewardGrant> findById(UUID rewardId);

    /** 원본 한 건에 보상 한 건 — 재시도가 두 번 요청하는 걸 막는 조회다. */
    Optional<RewardGrant> findByReference(RewardSource source, UUID referenceId);

    /** 지급일이 지난 대기분. 정산 스케줄러가 부른다. */
    List<RewardGrant> findDue(LocalDate on, int limit);

    List<RewardGrant> findByMember(String memberRef, int limit);

    RewardGrant save(RewardGrant grant);
}
