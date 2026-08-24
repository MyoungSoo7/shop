package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.application.port.out.SaveMembershipApprovalPort;
import github.lms.lemuel.user.domain.MembershipApproval;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 회원 승인 이력 영속성 어댑터.
 */
@Repository
@RequiredArgsConstructor
public class MembershipApprovalPersistenceAdapter implements SaveMembershipApprovalPort {

    private final SpringDataMembershipApprovalRepository repository;

    @Override
    public MembershipApproval save(MembershipApproval approval) {
        MembershipApprovalJpaEntity entity = new MembershipApprovalJpaEntity();
        entity.setUserId(approval.getUserId());
        entity.setAction(approval.getAction().name());
        entity.setReason(approval.getReason());
        entity.setProcessedBy(approval.getProcessedBy());
        entity.setCreatedAt(approval.getCreatedAt());

        MembershipApprovalJpaEntity saved = repository.save(entity);
        approval.assignId(saved.getId());
        return approval;
    }
}
