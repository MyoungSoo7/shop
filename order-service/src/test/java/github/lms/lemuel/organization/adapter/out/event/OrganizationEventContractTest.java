package github.lms.lemuel.organization.adapter.out.event;

import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.MembershipStatus;
import github.lms.lemuel.organization.domain.OrgRole;
import github.lms.lemuel.organization.domain.Organization;
import github.lms.lemuel.organization.domain.OrganizationStatus;
import github.lms.lemuel.organization.domain.OrganizationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — organization 이 발행하는 이벤트가 shared-common 의
 * 계약 스키마(lemuel.organization.created / lemuel.organization.member_joined)를 만족해야 한다.
 * 계약 드리프트를 런타임(DLT/무성 null)이 아닌 빌드 시점에 차단한다.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    OrganizationEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new OrganizationEventPublisherAdapter(saveOutboxEventPort, OutboxJson.mapper());
    }

    @Test
    @DisplayName("OrganizationCreated 페이로드는 lemuel.organization.created 계약을 만족한다")
    void organizationCreated_satisfiesContract() {
        Organization org = Organization.builder()
                .id(3001L)
                .name("무신사 스토어")
                .type(OrganizationType.SELLER)
                .externalRef("SELLER-777")
                .status(OrganizationStatus.ACTIVE)
                .build();

        publisher.publishCreated(org, 777L);

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        EventContractValidator.assertValid("lemuel.organization.created", outboxCaptor.getValue().getPayload());
    }

    @Test
    @DisplayName("OrganizationMemberJoined 페이로드는 lemuel.organization.member_joined 계약을 만족한다")
    void memberJoined_satisfiesContract() {
        Membership membership = Membership.builder()
                .id(9001L)
                .organizationId(3001L)
                .userId(888L)
                .role(OrgRole.MANAGER)
                .status(MembershipStatus.ACTIVE)
                .invitedBy(777L)
                .build();

        publisher.publishMemberJoined(membership);

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        EventContractValidator.assertValid("lemuel.organization.member_joined", outboxCaptor.getValue().getPayload());
    }

    @Test
    @DisplayName("MemberRemoved 페이로드는 lemuel.organization.member_removed 계약을 만족한다")
    void memberRemoved_satisfiesContract() {
        Membership membership = Membership.builder()
                .id(9001L)
                .organizationId(3001L)
                .userId(888L)
                .role(OrgRole.STAFF)
                .status(MembershipStatus.REMOVED)
                .invitedBy(777L)
                .build();

        publisher.publishMemberRemoved(membership);

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        EventContractValidator.assertValid(
                "lemuel.organization.member_removed", outboxCaptor.getValue().getPayload());
    }

    @Test
    @DisplayName("MemberRoleChanged 페이로드는 이전/신규 역할을 모두 싣는다")
    void memberRoleChanged_satisfiesContract() {
        Membership membership = Membership.builder()
                .id(9001L)
                .organizationId(3001L)
                .userId(888L)
                .role(OrgRole.MANAGER)
                .status(MembershipStatus.ACTIVE)
                .invitedBy(777L)
                .build();

        publisher.publishMemberRoleChanged(membership, OrgRole.STAFF);

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        String payload = outboxCaptor.getValue().getPayload();
        EventContractValidator.assertValid("lemuel.organization.member_role_changed", payload);
        assertThat(payload).contains("\"previousRole\":\"STAFF\"").contains("\"newRole\":\"MANAGER\"");
    }
}
