package github.lms.lemuel.organization.adapter.in.web.dto;

import github.lms.lemuel.organization.domain.Membership;

/** 멤버십 응답 뷰. */
public record MembershipResponse(
        Long userId,
        String role,
        String status,
        Long invitedBy
) {
    public static MembershipResponse from(Membership m) {
        return new MembershipResponse(m.getUserId(), m.getRole().name(), m.getStatus().name(), m.getInvitedBy());
    }
}
