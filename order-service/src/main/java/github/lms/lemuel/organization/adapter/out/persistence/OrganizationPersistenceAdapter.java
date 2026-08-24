package github.lms.lemuel.organization.adapter.out.persistence;

import github.lms.lemuel.organization.application.port.out.LoadOrganizationPort;
import github.lms.lemuel.organization.application.port.out.SaveOrganizationPort;
import github.lms.lemuel.organization.domain.Organization;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class OrganizationPersistenceAdapter implements LoadOrganizationPort, SaveOrganizationPort {

    private final SpringDataOrganizationRepository repository;

    public OrganizationPersistenceAdapter(SpringDataOrganizationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Organization> findById(Long organizationId) {
        return repository.findById(organizationId).map(OrganizationJpaEntity::toDomain);
    }

    @Override
    public Organization save(Organization organization) {
        // 도메인 스냅샷 → detached 엔티티 재구성 후 merge. @Version 이 그대로 실려 낙관적 락이 동작한다.
        return repository.saveAndFlush(OrganizationJpaEntity.fromDomain(organization)).toDomain();
    }
}
