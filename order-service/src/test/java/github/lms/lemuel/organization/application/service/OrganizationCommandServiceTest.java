package github.lms.lemuel.organization.application.service;

import github.lms.lemuel.organization.application.port.in.OrganizationCommandUseCase.CreateOrganizationCommand;
import github.lms.lemuel.organization.application.port.out.PublishOrganizationEventPort;
import github.lms.lemuel.organization.application.port.out.SaveMembershipPort;
import github.lms.lemuel.organization.application.port.out.SaveOrganizationPort;
import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.MembershipStatus;
import github.lms.lemuel.organization.domain.OrgRole;
import github.lms.lemuel.organization.domain.Organization;
import github.lms.lemuel.organization.domain.OrganizationStatus;
import github.lms.lemuel.organization.domain.OrganizationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrganizationCommandServiceTest {

    private SaveOrganizationPort saveOrg;
    private SaveMembershipPort saveMembership;
    private PublishOrganizationEventPort publish;
    private OrganizationCommandService service;

    @BeforeEach
    void setUp() {
        saveOrg = mock(SaveOrganizationPort.class);
        saveMembership = mock(SaveMembershipPort.class);
        publish = mock(PublishOrganizationEventPort.class);
        service = new OrganizationCommandService(saveOrg, saveMembership, publish);
    }

    @Test
    @DisplayName("조직 생성 시 생성자를 OWNER 로 등록하고 created 이벤트를 발행한다")
    void create_registersOwnerAndPublishes() {
        Organization persisted = Organization.builder().id(1L).name("무신사")
                .type(OrganizationType.SELLER).status(OrganizationStatus.ACTIVE).build();
        when(saveOrg.save(any())).thenReturn(persisted);
        when(saveMembership.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Organization result = service.create(
                new CreateOrganizationCommand("무신사", OrganizationType.SELLER, "123456", 100L));

        assertThat(result.getId()).isEqualTo(1L);

        ArgumentCaptor<Membership> owner = ArgumentCaptor.forClass(Membership.class);
        verify(saveMembership).save(owner.capture());
        assertThat(owner.getValue().getRole()).isEqualTo(OrgRole.OWNER);
        assertThat(owner.getValue().getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(owner.getValue().getUserId()).isEqualTo(100L);
        assertThat(owner.getValue().getOrganizationId()).isEqualTo(1L);

        verify(publish).publishCreated(eq(persisted), eq(100L));
    }
}
