package github.lms.lemuel.organization.adapter.out.persistence;

import github.lms.lemuel.organization.application.port.out.LoadMembershipPort;
import github.lms.lemuel.organization.application.port.out.SaveMembershipPort;
import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.MembershipStatus;
import github.lms.lemuel.organization.domain.OrgRole;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class MembershipPersistenceAdapter implements LoadMembershipPort, SaveMembershipPort {

    private static final Set<MembershipStatus> ACTIVE_SLOT =
            EnumSet.of(MembershipStatus.INVITED, MembershipStatus.ACTIVE);

    private final SpringDataMembershipRepository repository;

    public MembershipPersistenceAdapter(SpringDataMembershipRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Membership> findSlotOccupant(Long organizationId, Long userId) {
        return repository.findFirstByOrganizationIdAndUserIdAndStatusIn(organizationId, userId, ACTIVE_SLOT)
                .map(MembershipJpaEntity::toDomain);
    }

    @Override
    public Optional<Membership> findActiveMember(Long organizationId, Long userId) {
        return repository.findFirstByOrganizationIdAndUserIdAndStatus(organizationId, userId, MembershipStatus.ACTIVE)
                .map(MembershipJpaEntity::toDomain);
    }

    @Override
    public List<Membership> findByOrganization(Long organizationId) {
        return repository.findByOrganizationId(organizationId).stream()
                .map(MembershipJpaEntity::toDomain)
                .toList();
    }

    @Override
    public long countActiveOwners(Long organizationId) {
        return repository.countByOrganizationIdAndRoleAndStatus(
                organizationId, OrgRole.OWNER, MembershipStatus.ACTIVE);
    }

    @Override
    public Membership save(Membership membership) {
        return repository.saveAndFlush(MembershipJpaEntity.fromDomain(membership)).toDomain();
    }
}
