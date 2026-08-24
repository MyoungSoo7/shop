package github.lms.lemuel.organization.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.organization.adapter.in.web.dto.ChangeRoleRequest;
import github.lms.lemuel.organization.adapter.in.web.dto.CreateOrganizationRequest;
import github.lms.lemuel.organization.adapter.in.web.dto.InviteMemberRequest;
import github.lms.lemuel.organization.adapter.in.web.dto.MembershipResponse;
import github.lms.lemuel.organization.adapter.in.web.dto.OrganizationResponse;
import github.lms.lemuel.organization.application.port.in.MembershipCommandUseCase;
import github.lms.lemuel.organization.application.port.in.MembershipCommandUseCase.ChangeRoleCommand;
import github.lms.lemuel.organization.application.port.in.MembershipCommandUseCase.InviteCommand;
import github.lms.lemuel.organization.application.port.in.OrganizationCommandUseCase;
import github.lms.lemuel.organization.application.port.in.OrganizationCommandUseCase.CreateOrganizationCommand;
import github.lms.lemuel.organization.application.port.in.OrganizationQueryUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 조직·멤버십 관리 API. JWT 인증 필수(shared-common SecurityConfig anyRequest authenticated).
 *
 * <p>★ 요청자 식별자(userId)는 요청 바디/파라미터가 아니라 JWT 주체에서 파생한다(IDOR 방지). 조직 내 역할 기반
 * 인가는 애플리케이션 서비스({@code OrgAuthorizer})가 판정한다.
 */
@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final OrganizationCommandUseCase organizationCommandUseCase;
    private final MembershipCommandUseCase membershipCommandUseCase;
    private final OrganizationQueryUseCase organizationQueryUseCase;

    public OrganizationController(OrganizationCommandUseCase organizationCommandUseCase,
                                  MembershipCommandUseCase membershipCommandUseCase,
                                  OrganizationQueryUseCase organizationQueryUseCase) {
        this.organizationCommandUseCase = organizationCommandUseCase;
        this.membershipCommandUseCase = membershipCommandUseCase;
        this.organizationQueryUseCase = organizationQueryUseCase;
    }

    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest req,
                                                       Authentication authentication) {
        var org = organizationCommandUseCase.create(new CreateOrganizationCommand(
                req.name(), req.type(), req.externalRef(), callerUserId(authentication)));
        return ResponseEntity.status(HttpStatus.CREATED).body(OrganizationResponse.from(org));
    }

    @GetMapping("/{orgId}")
    public ResponseEntity<OrganizationResponse> get(@PathVariable Long orgId, Authentication authentication) {
        return ResponseEntity.ok(OrganizationResponse.fromView(
                organizationQueryUseCase.getOrganization(orgId, callerUserId(authentication))));
    }

    @PostMapping("/{orgId}/members")
    public ResponseEntity<MembershipResponse> invite(@PathVariable Long orgId,
                                                     @Valid @RequestBody InviteMemberRequest req,
                                                     Authentication authentication) {
        var m = membershipCommandUseCase.invite(new InviteCommand(
                orgId, req.targetUserId(), req.role(), callerUserId(authentication)));
        return ResponseEntity.status(HttpStatus.CREATED).body(MembershipResponse.from(m));
    }

    @PostMapping("/{orgId}/members/accept")
    public ResponseEntity<MembershipResponse> accept(@PathVariable Long orgId, Authentication authentication) {
        var m = membershipCommandUseCase.accept(orgId, callerUserId(authentication));
        return ResponseEntity.ok(MembershipResponse.from(m));
    }

    @PatchMapping("/{orgId}/members/{userId}/role")
    public ResponseEntity<MembershipResponse> changeRole(@PathVariable Long orgId,
                                                         @PathVariable Long userId,
                                                         @Valid @RequestBody ChangeRoleRequest req,
                                                         Authentication authentication) {
        var m = membershipCommandUseCase.changeRole(new ChangeRoleCommand(
                orgId, userId, req.newRole(), callerUserId(authentication)));
        return ResponseEntity.ok(MembershipResponse.from(m));
    }

    @DeleteMapping("/{orgId}/members/{userId}")
    public ResponseEntity<Void> remove(@PathVariable Long orgId, @PathVariable Long userId,
                                       Authentication authentication) {
        membershipCommandUseCase.remove(orgId, userId, callerUserId(authentication));
        return ResponseEntity.noContent().build();
    }

    /** JWT 인증 주체에서 userId 를 추출한다. 미인증/식별불가면 403. */
    private static Long callerUserId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return principal.userId();
        }
        throw new AccessDeniedException("인증 주체에서 사용자 식별자를 확인할 수 없습니다.");
    }
}
