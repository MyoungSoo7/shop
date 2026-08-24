package github.lms.lemuel.organization.application.service;

import github.lms.lemuel.organization.application.exception.ForbiddenOrgAccessException;
import github.lms.lemuel.organization.application.port.in.OrganizationQueryUseCase.OrganizationView;
import github.lms.lemuel.organization.application.port.out.LoadMembershipPort;
import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.MembershipStatus;
import github.lms.lemuel.organization.domain.OrgRole;
import github.lms.lemuel.organization.domain.Organization;
import github.lms.lemuel.organization.domain.OrganizationStatus;
import github.lms.lemuel.organization.domain.OrganizationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationQueryServiceTest {

    private OrgAuthorizer authorizer;
    private LoadMembershipPort loadMembership;
    private OrganizationQueryService service;

    @BeforeEach
    void setUp() {
        authorizer = mock(OrgAuthorizer.class);
        loadMembership = mock(LoadMembershipPort.class);
        service = new OrganizationQueryService(authorizer, loadMembership);
    }

    @Test
    @DisplayName("활성 멤버는 조직+멤버 목록을 조회한다")
    void get_success() {
        Organization org = Organization.builder().id(1L).name("셀러")
                .type(OrganizationType.SELLER).status(OrganizationStatus.ACTIVE).build();
        when(authorizer.requireOrganization(1L)).thenReturn(org);
        Membership owner = Membership.builder().id(1L).organizationId(1L).userId(100L)
                .role(OrgRole.OWNER).status(MembershipStatus.ACTIVE).build();
        when(loadMembership.findByOrganization(1L)).thenReturn(List.of(owner));

        OrganizationView view = service.getOrganization(1L, 100L);

        assertThat(view.organization().getId()).isEqualTo(1L);
        assertThat(view.members()).hasSize(1);
    }

    @Test
    @DisplayName("활성 멤버가 아니면 403(타 조직 접근 포함)")
    void get_forbiddenForNonMember() {
        Organization org = Organization.builder().id(1L).name("셀러")
                .type(OrganizationType.SELLER).status(OrganizationStatus.ACTIVE).build();
        when(authorizer.requireOrganization(1L)).thenReturn(org);
        when(authorizer.requireActiveMember(1L, 999L))
                .thenThrow(new ForbiddenOrgAccessException("활성 멤버 아님"));

        assertThatThrownBy(() -> service.getOrganization(1L, 999L))
                .isInstanceOf(ForbiddenOrgAccessException.class);
    }
}
