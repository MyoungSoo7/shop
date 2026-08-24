package github.lms.lemuel.organization.application.port.in;

import github.lms.lemuel.organization.domain.Organization;
import github.lms.lemuel.organization.domain.OrganizationType;

/** 조직 생성 커맨드 유스케이스. */
public interface OrganizationCommandUseCase {

    /**
     * 조직을 생성하고 요청자(actingUserId)를 OWNER 멤버로 자동 등록한다(원자적).
     * organization.created 이벤트를 Outbox 로 발행한다.
     */
    Organization create(CreateOrganizationCommand command);

    record CreateOrganizationCommand(String name, OrganizationType type, String externalRef, Long actingUserId) {
    }
}
