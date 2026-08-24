package github.lms.lemuel.organization.application.port.out;

import github.lms.lemuel.organization.domain.Organization;

import java.util.Optional;

public interface LoadOrganizationPort {

    Optional<Organization> findById(Long organizationId);
}
