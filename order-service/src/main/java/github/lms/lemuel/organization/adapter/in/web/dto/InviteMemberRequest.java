package github.lms.lemuel.organization.adapter.in.web.dto;

import github.lms.lemuel.organization.domain.OrgRole;
import jakarta.validation.constraints.NotNull;

/** 멤버 초대 요청 — 초대 대상 userId 와 부여할 역할. */
public record InviteMemberRequest(
        @NotNull Long targetUserId,
        @NotNull OrgRole role
) {
}
