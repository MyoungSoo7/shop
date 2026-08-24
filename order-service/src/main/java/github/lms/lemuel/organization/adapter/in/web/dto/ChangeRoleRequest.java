package github.lms.lemuel.organization.adapter.in.web.dto;

import github.lms.lemuel.organization.domain.OrgRole;
import jakarta.validation.constraints.NotNull;

/** 멤버 역할 변경 요청. */
public record ChangeRoleRequest(
        @NotNull OrgRole newRole
) {
}
