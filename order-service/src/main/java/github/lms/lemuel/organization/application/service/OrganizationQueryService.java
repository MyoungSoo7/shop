package github.lms.lemuel.organization.application.service;

import github.lms.lemuel.organization.application.port.in.OrganizationQueryUseCase;
import github.lms.lemuel.organization.application.port.out.LoadMembershipPort;
import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.Organization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 조직 조회 서비스 — 조직 단건 + 멤버 목록. 조직의 활성 멤버만 조회 가능(IDOR). */
@Service
public class OrganizationQueryService implements OrganizationQueryUseCase {

    private final OrgAuthorizer authorizer;
    private final LoadMembershipPort loadMembershipPort;

    public OrganizationQueryService(OrgAuthorizer authorizer, LoadMembershipPort loadMembershipPort) {
        this.authorizer = authorizer;
        this.loadMembershipPort = loadMembershipPort;
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationView getOrganization(Long organizationId, Long actingUserId) {
        Organization organization = authorizer.requireOrganization(organizationId);
        authorizer.requireActiveMember(organizationId, actingUserId);   // 활성 멤버만 열람
        List<Membership> members = loadMembershipPort.findByOrganization(organizationId);
        return new OrganizationView(organization, members);
    }
}
