package github.lms.lemuel.organization.application.port.out;

import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.OrgRole;
import github.lms.lemuel.organization.domain.Organization;

/**
 * 조직 도메인 이벤트 발행 포트 — Transactional Outbox 로 기록되어 도메인 트랜잭션과 원자적으로 커밋된다.
 * shared-common OutboxPublisherScheduler 가 aggregateType="Organization"+eventType 으로 라우팅해 발행한다.
 */
public interface PublishOrganizationEventPort {

    /** organization.created — 조직 생성(생성자 OWNER 자동 등록 포함). */
    void publishCreated(Organization organization, Long ownerUserId);

    /** organization.member_joined — 초대 수락으로 멤버가 ACTIVE 가 됨. */
    void publishMemberJoined(Membership membership);

    /**
     * organization.member_role_changed — 활성 멤버의 역할이 변경됨.
     * {@code previousRole} 은 변경 전 역할을 호출측이 별도로 포착해 넘겨야 한다(도메인 객체는 이미 새 역할로 갱신된 상태).
     */
    void publishMemberRoleChanged(Membership membership, OrgRole previousRole);

    /**
     * organization.member_removed — 멤버십이 REMOVED(터미널)로 전이됨.
     * 소비측(card-service)이 해당 임직원의 카드를 정지시키는 근거가 되므로, 저장이 실제로 커밋되는 경로에서만 발행해야 한다.
     */
    void publishMemberRemoved(Membership membership);
}
