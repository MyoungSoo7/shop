package github.lms.lemuel.organization.application.port.out;

import github.lms.lemuel.organization.domain.Organization;

public interface SaveOrganizationPort {

    /** 신규(id null)면 INSERT, 기존이면 낙관적 락(@Version) 갱신. 영속 id 가 채워진 조직을 반환. */
    Organization save(Organization organization);
}
