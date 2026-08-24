package github.lms.lemuel.organization.application.service;

import github.lms.lemuel.organization.application.exception.ForbiddenOrgAccessException;
import github.lms.lemuel.organization.application.exception.OrganizationNotFoundException;
import github.lms.lemuel.organization.application.port.out.LoadMembershipPort;
import github.lms.lemuel.organization.application.port.out.LoadOrganizationPort;
import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.OrgRole;
import github.lms.lemuel.organization.domain.Organization;
import org.springframework.stereotype.Component;

import java.util.function.Predicate;

/**
 * 조직 인가 판정 — 항상 JWT 주체(actingUserId)의 조직 내 <b>활성</b> 역할로 판정한다.
 * 요청 파라미터의 조직/역할을 신뢰하지 않는다(IDOR 방지).
 */
@Component
public class OrgAuthorizer {

    private final LoadOrganizationPort loadOrganizationPort;
    private final LoadMembershipPort loadMembershipPort;

    public OrgAuthorizer(LoadOrganizationPort loadOrganizationPort, LoadMembershipPort loadMembershipPort) {
        this.loadOrganizationPort = loadOrganizationPort;
        this.loadMembershipPort = loadMembershipPort;
    }

    /** 조직 존재 확인 — 없으면 404. */
    public Organization requireOrganization(Long organizationId) {
        return loadOrganizationPort.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException(organizationId));
    }

    /** 요청자가 조직의 활성 멤버여야 함 — 아니면 403(타 조직 접근 포함). 역할 판정의 기준 멤버십을 반환. */
    public Membership requireActiveMember(Long organizationId, Long actingUserId) {
        return loadMembershipPort.findActiveMember(organizationId, actingUserId)
                .orElseThrow(() -> new ForbiddenOrgAccessException(
                        "조직 %d 의 활성 멤버가 아닙니다".formatted(organizationId)));
    }

    /** 요청자가 활성 멤버이면서 지정 역할 조건을 만족해야 함 — 아니면 403. */
    public Membership requireRole(Long organizationId, Long actingUserId, Predicate<OrgRole> allowed, String action) {
        Membership caller = requireActiveMember(organizationId, actingUserId);
        if (!allowed.test(caller.getRole())) {
            throw new ForbiddenOrgAccessException(
                    "권한 없음: %s (현재 역할 %s)".formatted(action, caller.getRole()));
        }
        return caller;
    }
}
