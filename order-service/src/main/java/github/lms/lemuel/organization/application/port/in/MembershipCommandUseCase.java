package github.lms.lemuel.organization.application.port.in;

import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.OrgRole;

/**
 * 멤버십 커맨드 유스케이스 — 초대·수락·역할변경·제거.
 *
 * <p>모든 커맨드의 인가는 actingUserId(JWT 주체)의 조직 내 활성 역할로 판정한다(IDOR 방지).
 */
public interface MembershipCommandUseCase {

    /** 멤버 초대(OWNER/MANAGER) — INVITED 멤버십 생성. */
    Membership invite(InviteCommand command);

    /** 초대 수락(초대 대상 본인) — INVITED → ACTIVE, organization.member_joined 발행. */
    Membership accept(Long organizationId, Long actingUserId);

    /** 역할 변경(OWNER) — 마지막 OWNER 강등 차단. */
    Membership changeRole(ChangeRoleCommand command);

    /** 멤버 제거(OWNER) — 마지막 OWNER 제거 차단. 활성 슬롯을 비워 재초대 가능. */
    void remove(Long organizationId, Long targetUserId, Long actingUserId);

    record InviteCommand(Long organizationId, Long targetUserId, OrgRole role, Long actingUserId) {
    }

    record ChangeRoleCommand(Long organizationId, Long targetUserId, OrgRole newRole, Long actingUserId) {
    }
}
