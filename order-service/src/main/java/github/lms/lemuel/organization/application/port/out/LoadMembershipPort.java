package github.lms.lemuel.organization.application.port.out;

import github.lms.lemuel.organization.domain.Membership;

import java.util.List;
import java.util.Optional;

public interface LoadMembershipPort {

    /** (org,user) 의 활성 슬롯 점유자 — INVITED 또는 ACTIVE (uq_membership_active 가 최대 1건 보장). */
    Optional<Membership> findSlotOccupant(Long organizationId, Long userId);

    /** 인가 판정용 — 해당 조직의 ACTIVE 멤버(역할 lookup). SUSPENDED/INVITED 는 권한 없음. */
    Optional<Membership> findActiveMember(Long organizationId, Long userId);

    /** 조직의 전체 멤버 목록. */
    List<Membership> findByOrganization(Long organizationId);

    /** 조직의 활성 OWNER 수 — 마지막 OWNER 불변식 판정. */
    long countActiveOwners(Long organizationId);
}
