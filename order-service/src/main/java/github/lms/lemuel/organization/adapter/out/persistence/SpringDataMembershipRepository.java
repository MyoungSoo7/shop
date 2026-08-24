package github.lms.lemuel.organization.adapter.out.persistence;

import github.lms.lemuel.organization.domain.MembershipStatus;
import github.lms.lemuel.organization.domain.OrgRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SpringDataMembershipRepository extends JpaRepository<MembershipJpaEntity, Long> {

    /** 활성 슬롯 점유자 조회 — statuses = (INVITED, ACTIVE). uq_membership_active 가 최대 1건 보장. */
    Optional<MembershipJpaEntity> findFirstByOrganizationIdAndUserIdAndStatusIn(
            Long organizationId, Long userId, Collection<MembershipStatus> statuses);

    /** 특정 상태 멤버십 조회 (인가용 ACTIVE 멤버 등). */
    Optional<MembershipJpaEntity> findFirstByOrganizationIdAndUserIdAndStatus(
            Long organizationId, Long userId, MembershipStatus status);

    List<MembershipJpaEntity> findByOrganizationId(Long organizationId);

    long countByOrganizationIdAndRoleAndStatus(Long organizationId, OrgRole role, MembershipStatus status);
}
