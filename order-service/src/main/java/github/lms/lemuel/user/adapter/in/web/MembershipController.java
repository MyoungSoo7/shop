package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.user.adapter.in.web.response.MembershipResponse;
import github.lms.lemuel.user.application.port.in.ApproveMembershipUseCase;
import github.lms.lemuel.user.application.port.in.GetPendingMembersUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.InvalidCredentialsException;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 회원 승인 워크플로 API (관리자 전용).
 *
 * 업체 회원/시공기사는 가입 후 PENDING 상태이며, 관리자가 승인/반려/정지/정지해제 한다.
 * 모든 처리는 감사 이력(membership_approvals)에 기록된다.
 */
@Tag(name = "Membership", description = "회원 승인 워크플로 API (관리자)")
@RestController
@RequestMapping("/memberships")
@RequiredArgsConstructor
public class MembershipController {

    private final ApproveMembershipUseCase approveMembershipUseCase;
    private final GetPendingMembersUseCase getPendingMembersUseCase;
    private final LoadUserPort loadUserPort;

    @Operation(summary = "승인 대기 회원 목록", description = "PENDING 상태 회원을 가입 순으로 조회한다.")
    @GetMapping("/pending")
    public ResponseEntity<List<MembershipResponse>> pending() {
        requireAdmin();
        List<MembershipResponse> result = getPendingMembersUseCase.getPendingMembers()
                .stream()
                .map(MembershipResponse::from)
                .toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "회원 승인", description = "PENDING 회원을 승인한다. (→ APPROVED)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승인 성공"),
            @ApiResponse(responseCode = "403", description = "관리자만 처리 가능"),
            @ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "현재 상태에서 승인 불가")
    })
    @PostMapping("/{userId}/approve")
    public ResponseEntity<MembershipResponse> approve(@PathVariable Long userId) {
        Long admin = requireAdmin().getId();
        return ResponseEntity.ok(MembershipResponse.from(approveMembershipUseCase.approve(userId, admin)));
    }

    @Operation(summary = "회원 반려", description = "PENDING 회원을 반려한다. (→ REJECTED)")
    @PostMapping("/{userId}/reject")
    public ResponseEntity<MembershipResponse> reject(
            @PathVariable Long userId,
            @Valid @RequestBody(required = false) MembershipActionRequest request) {
        Long admin = requireAdmin().getId();
        return ResponseEntity.ok(MembershipResponse.from(
                approveMembershipUseCase.reject(userId, reasonOf(request), admin)));
    }

    @Operation(summary = "회원 정지", description = "APPROVED 회원을 정지한다. (→ SUSPENDED)")
    @PostMapping("/{userId}/suspend")
    public ResponseEntity<MembershipResponse> suspend(
            @PathVariable Long userId,
            @Valid @RequestBody(required = false) MembershipActionRequest request) {
        Long admin = requireAdmin().getId();
        return ResponseEntity.ok(MembershipResponse.from(
                approveMembershipUseCase.suspend(userId, reasonOf(request), admin)));
    }

    @Operation(summary = "정지 해제", description = "SUSPENDED 회원의 정지를 해제한다. (→ APPROVED)")
    @PostMapping("/{userId}/reinstate")
    public ResponseEntity<MembershipResponse> reinstate(@PathVariable Long userId) {
        Long admin = requireAdmin().getId();
        return ResponseEntity.ok(MembershipResponse.from(approveMembershipUseCase.reinstate(userId, admin)));
    }

    private String reasonOf(MembershipActionRequest request) {
        return request != null ? request.reason() : null;
    }

    private User requireAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new InvalidCredentialsException("Authentication required");
        }
        User user = loadUserPort.findByEmail(authentication.getName())
                .orElseThrow(() -> new UserNotFoundException(authentication.getName()));
        if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.MANAGER) {
            throw new InvalidCredentialsException("Only ADMIN or MANAGER can process memberships");
        }
        return user;
    }

    public record MembershipActionRequest(
            @Size(max = 500, message = "사유는 500자 이내여야 합니다") String reason
    ) {}
}
