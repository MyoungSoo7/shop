package github.lms.lemuel.organization.application.port.in;

import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.Organization;

import java.util.List;

/** 조직 조회 유스케이스 — 조직 단건 + 멤버 목록. 조회도 조직의 활성 멤버만 허용(IDOR). */
public interface OrganizationQueryUseCase {

    OrganizationView getOrganization(Long organizationId, Long actingUserId);

    record OrganizationView(Organization organization, List<Membership> members) {
    }
}
