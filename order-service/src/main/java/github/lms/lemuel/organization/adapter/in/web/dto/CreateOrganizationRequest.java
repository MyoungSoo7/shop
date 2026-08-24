package github.lms.lemuel.organization.adapter.in.web.dto;

import github.lms.lemuel.organization.domain.OrganizationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 조직 생성 요청. 생성자(OWNER)는 JWT 주체에서 파생하므로 바디에 없다(IDOR 방지). */
public record CreateOrganizationRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull OrganizationType type,
        @Size(max = 64) String externalRef
) {
}
