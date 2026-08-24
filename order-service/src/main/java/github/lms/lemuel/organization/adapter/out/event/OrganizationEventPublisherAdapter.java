package github.lms.lemuel.organization.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.organization.application.port.out.PublishOrganizationEventPort;
import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.OrgRole;
import github.lms.lemuel.organization.domain.Organization;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 조직 도메인 이벤트를 Transactional Outbox 에 기록한다 — 도메인 트랜잭션과 같은 트랜잭션에서 저장되어
 * 원자성이 보장되고, shared-common OutboxPublisherScheduler 가 aggregateType="Organization"+eventType 으로
 * 라우팅해 토픽 {@code lemuel.organization.created} / {@code lemuel.organization.member_joined} /
 * {@code lemuel.organization.member_role_changed} / {@code lemuel.organization.member_removed} 로 발행한다.
 */
@Component
public class OrganizationEventPublisherAdapter implements PublishOrganizationEventPort {

    private static final String AGGREGATE_TYPE = "Organization";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public OrganizationEventPublisherAdapter(SaveOutboxEventPort saveOutboxEventPort,
                                             @Qualifier("outboxObjectMapper") ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishCreated(Organization organization, Long ownerUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organization.getId());
        payload.put("name", organization.getName());
        payload.put("type", organization.getType().name());
        payload.put("externalRef", organization.getExternalRef());
        payload.put("ownerUserId", ownerUserId);
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(organization.getId()),
                "OrganizationCreated",
                toJson(payload)));
    }

    @Override
    public void publishMemberJoined(Membership membership) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", membership.getOrganizationId());
        payload.put("userId", membership.getUserId());
        payload.put("role", membership.getRole().name());
        payload.put("membershipId", membership.getId());
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(membership.getOrganizationId()),
                "OrganizationMemberJoined",
                toJson(payload)));
    }

    @Override
    public void publishMemberRoleChanged(Membership membership, OrgRole previousRole) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", membership.getOrganizationId());
        payload.put("userId", membership.getUserId());
        payload.put("membershipId", membership.getId());
        payload.put("previousRole", previousRole.name());
        payload.put("newRole", membership.getRole().name());
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(membership.getOrganizationId()),
                "OrganizationMemberRoleChanged",
                toJson(payload)));
    }

    @Override
    public void publishMemberRemoved(Membership membership) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", membership.getOrganizationId());
        payload.put("userId", membership.getUserId());
        payload.put("membershipId", membership.getId());
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(membership.getOrganizationId()),
                "OrganizationMemberRemoved",
                toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("organization 이벤트 직렬화 실패", e);
        }
    }
}
