package github.lms.lemuel.organization.application.service;

import github.lms.lemuel.organization.application.exception.DuplicateMembershipException;
import github.lms.lemuel.organization.application.exception.MembershipNotFoundException;
import github.lms.lemuel.organization.application.port.in.MembershipCommandUseCase;
import github.lms.lemuel.organization.application.port.out.LoadMembershipPort;
import github.lms.lemuel.organization.application.port.out.PublishOrganizationEventPort;
import github.lms.lemuel.organization.application.port.out.SaveMembershipPort;
import github.lms.lemuel.organization.domain.LastOwnerException;
import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.OrgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멤버십 커맨드 서비스 — 초대·수락·역할변경·제거. 인가는 {@link OrgAuthorizer} 로 JWT 주체 역할 기반 판정,
 * 마지막 OWNER 불변식은 활성 OWNER 수를 세어 강제한다.
 */
@Service
public class MembershipCommandService implements MembershipCommandUseCase {

    private static final Logger log = LoggerFactory.getLogger(MembershipCommandService.class);

    private final OrgAuthorizer authorizer;
    private final LoadMembershipPort loadMembershipPort;
    private final SaveMembershipPort saveMembershipPort;
    private final PublishOrganizationEventPort publishPort;

    public MembershipCommandService(OrgAuthorizer authorizer, LoadMembershipPort loadMembershipPort,
                                    SaveMembershipPort saveMembershipPort, PublishOrganizationEventPort publishPort) {
        this.authorizer = authorizer;
        this.loadMembershipPort = loadMembershipPort;
        this.saveMembershipPort = saveMembershipPort;
        this.publishPort = publishPort;
    }

    @Override
    @Transactional
    public Membership invite(InviteCommand command) {
        authorizer.requireOrganization(command.organizationId());
        authorizer.requireRole(command.organizationId(), command.actingUserId(),
                OrgRole::canInviteMembers, "멤버 초대");

        // 이미 활성 슬롯(INVITED/ACTIVE)을 점유 중이면 중복 — uq_membership_active 가 최종 방어선이지만 선검증으로 409 명확화
        loadMembershipPort.findSlotOccupant(command.organizationId(), command.targetUserId())
                .ifPresent(m -> {
                    throw new DuplicateMembershipException(command.organizationId(), command.targetUserId());
                });

        Membership saved = saveMembershipPort.save(Membership.invite(
                command.organizationId(), command.targetUserId(), command.role(), command.actingUserId()));
        log.info("멤버 초대: org={} target={} role={} by={}",
                command.organizationId(), command.targetUserId(), command.role(), command.actingUserId());
        return saved;
    }

    @Override
    @Transactional
    public Membership accept(Long organizationId, Long actingUserId) {
        // 초대 대상 본인만 수락 가능 — 슬롯 점유자를 조회하면 (org, actingUser) 로 키가 잡히므로 타인 초대 수락 불가
        Membership membership = loadMembershipPort.findSlotOccupant(organizationId, actingUserId)
                .orElseThrow(() -> new MembershipNotFoundException(organizationId, actingUserId));
        membership.accept();   // INVITED → ACTIVE (이미 ACTIVE 면 상태머신 위반 → 409)
        Membership saved = saveMembershipPort.save(membership);
        publishPort.publishMemberJoined(saved);
        log.info("초대 수락: org={} user={} role={}", organizationId, actingUserId, saved.getRole());
        return saved;
    }

    @Override
    @Transactional
    public Membership changeRole(ChangeRoleCommand command) {
        authorizer.requireOrganization(command.organizationId());
        authorizer.requireRole(command.organizationId(), command.actingUserId(),
                OrgRole::canManageMembers, "역할 변경");

        Membership target = loadMembershipPort.findActiveMember(command.organizationId(), command.targetUserId())
                .orElseThrow(() -> new MembershipNotFoundException(command.organizationId(), command.targetUserId()));

        // 마지막 OWNER 강등 차단
        if (target.getRole() == OrgRole.OWNER && command.newRole() != OrgRole.OWNER
                && loadMembershipPort.countActiveOwners(command.organizationId()) <= 1) {
            throw new LastOwnerException(command.organizationId());
        }

        // 발행 시점에는 도메인 객체가 이미 새 역할로 바뀌어 있으므로, 바꾸기 직전에 이전 역할을 별도로 포착해둔다.
        OrgRole previousRole = target.getRole();
        target.changeRole(command.newRole());
        Membership saved = saveMembershipPort.save(target);
        // 같은 역할로의 "변경" 요청은 실제 상태 전이가 아니므로 소비측에 의미 없는 이벤트를 보내지 않는다.
        if (previousRole != saved.getRole()) {
            publishPort.publishMemberRoleChanged(saved, previousRole);
        }
        log.info("역할 변경: org={} target={} → {}",
                command.organizationId(), command.targetUserId(), command.newRole());
        return saved;
    }

    @Override
    @Transactional
    public void remove(Long organizationId, Long targetUserId, Long actingUserId) {
        authorizer.requireOrganization(organizationId);
        authorizer.requireRole(organizationId, actingUserId, OrgRole::canManageMembers, "멤버 제거");

        Membership target = loadMembershipPort.findSlotOccupant(organizationId, targetUserId)
                .orElseThrow(() -> new MembershipNotFoundException(organizationId, targetUserId));

        // 마지막 활성 OWNER 제거 차단
        if (target.isActiveOwner() && loadMembershipPort.countActiveOwners(organizationId) <= 1) {
            throw new LastOwnerException(organizationId);
        }

        target.remove();
        Membership saved = saveMembershipPort.save(target);
        publishPort.publishMemberRemoved(saved);
        log.info("멤버 제거: org={} target={} by={}", organizationId, targetUserId, actingUserId);
    }
}
