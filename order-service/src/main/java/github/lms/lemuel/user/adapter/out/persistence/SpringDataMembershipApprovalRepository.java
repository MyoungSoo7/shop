package github.lms.lemuel.user.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataMembershipApprovalRepository
        extends JpaRepository<MembershipApprovalJpaEntity, Long> {

    List<MembershipApprovalJpaEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}
