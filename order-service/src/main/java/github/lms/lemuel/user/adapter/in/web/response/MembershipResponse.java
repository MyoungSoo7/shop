package github.lms.lemuel.user.adapter.in.web.response;

import github.lms.lemuel.user.domain.User;

import java.time.LocalDateTime;

/**
 * 회원 승인 처리 결과 / 승인 대기 회원 응답 DTO.
 */
public record MembershipResponse(
        Long id,
        String email,
        String name,
        String role,
        String membershipStatus,
        LocalDateTime createdAt
) {
    public static MembershipResponse from(User user) {
        return new MembershipResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.getMembershipStatus() == null ? null : user.getMembershipStatus().name(),
                user.getCreatedAt()
        );
    }
}
