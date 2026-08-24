package github.lms.lemuel.organization.adapter.in.web.dto;

import github.lms.lemuel.organization.application.port.in.OrganizationQueryUseCase.OrganizationView;
import github.lms.lemuel.organization.domain.Organization;

import java.util.List;

/** 조직 응답 뷰 — members 는 조회 시에만 채워지고 생성 응답에서는 빈 목록이다. */
public record OrganizationResponse(
        Long id,
        String name,
        String type,
        String externalRef,
        String status,
        List<MembershipResponse> members
) {
    public static OrganizationResponse from(Organization o) {
        return new OrganizationResponse(o.getId(), o.getName(), o.getType().name(),
                o.getExternalRef(), o.getStatus().name(), List.of());
    }

    public static OrganizationResponse fromView(OrganizationView view) {
        Organization o = view.organization();
        return new OrganizationResponse(o.getId(), o.getName(), o.getType().name(),
                o.getExternalRef(), o.getStatus().name(),
                view.members().stream().map(MembershipResponse::from).toList());
    }
}
