package github.lms.lemuel.organization.application.service;

import github.lms.lemuel.organization.application.exception.ForbiddenOrgAccessException;
import github.lms.lemuel.organization.application.exception.OrganizationNotFoundException;
import github.lms.lemuel.organization.application.port.out.LoadMembershipPort;
import github.lms.lemuel.organization.application.port.out.LoadOrganizationPort;
import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.MembershipStatus;
import github.lms.lemuel.organization.domain.OrgRole;
import github.lms.lemuel.organization.domain.Organization;
import github.lms.lemuel.organization.domain.OrganizationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 인가 판정의 단일 초크포인트.
 *
 * <p>여기서 판정이 흐물해지면 IDOR 이 된다 — 요청이 보낸 조직/역할이 아니라 <b>JWT 주체의
 * 조직 내 활성 역할</b>로만 판정해야 하고, 남의 조직은 "없음(404)"이 아니라 "권한 없음(403)"으로
 * 끊겨야 한다(조직 존재 여부 자체가 정보다).
 */
class OrgAuthorizerTest {

    private LoadOrganizationPort loadOrganization;
    private LoadMembershipPort loadMembership;
    private OrgAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        loadOrganization = mock(LoadOrganizationPort.class);
        loadMembership = mock(LoadMembershipPort.class);
        authorizer = new OrgAuthorizer(loadOrganization, loadMembership);
    }

    private static Organization organization() {
        return Organization.builder()
                .id(1L)
                .name("르뮤엘 셀러")
                .type(OrganizationType.SELLER)
                .status(github.lms.lemuel.organization.domain.OrganizationStatus.ACTIVE)
                .build();
    }

    private static Membership member(OrgRole role) {
        return Membership.builder()
                .id(9L).organizationId(1L).userId(100L)
                .role(role).status(MembershipStatus.ACTIVE).invitedBy(1L)
                .build();
    }

    @Nested
    @DisplayName("조직 존재 확인")
    class RequireOrganization {

        @Test
        @DisplayName("존재하면 그대로 돌려준다")
        void found() {
            when(loadOrganization.findById(1L)).thenReturn(Optional.of(organization()));

            assertThat(authorizer.requireOrganization(1L).getName()).isEqualTo("르뮤엘 셀러");
        }

        @Test
        @DisplayName("없으면 404 — 조직 id 를 메시지에 남긴다")
        void notFound() {
            when(loadOrganization.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authorizer.requireOrganization(404L))
                    .isInstanceOf(OrganizationNotFoundException.class)
                    .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("활성 멤버 확인")
    class RequireActiveMember {

        @Test
        @DisplayName("활성 멤버면 판정 기준이 될 멤버십을 돌려준다")
        void activeMember() {
            when(loadMembership.findActiveMember(1L, 100L)).thenReturn(Optional.of(member(OrgRole.STAFF)));

            assertThat(authorizer.requireActiveMember(1L, 100L).getRole()).isEqualTo(OrgRole.STAFF);
        }

        @Test
        @DisplayName("활성 멤버가 아니면 403 — 타 조직 접근도 여기서 끊긴다")
        void notActiveMember() {
            // INVITED·SUSPENDED·비멤버는 포트가 전부 empty 로 답한다. 어느 쪽이든 판정은 같다:
            // 조직이 있는지조차 알려주지 않는다.
            when(loadMembership.findActiveMember(1L, 999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authorizer.requireActiveMember(1L, 999L))
                    .isInstanceOf(ForbiddenOrgAccessException.class)
                    .hasMessageContaining("활성 멤버가 아닙니다");
            verifyNoInteractions(loadOrganization);
        }
    }

    @Nested
    @DisplayName("역할 조건 확인")
    class RequireRole {

        @Test
        @DisplayName("조건을 만족하면 호출자의 멤버십을 돌려준다")
        void allowed() {
            when(loadMembership.findActiveMember(1L, 100L)).thenReturn(Optional.of(member(OrgRole.OWNER)));

            Membership caller = authorizer.requireRole(1L, 100L, OrgRole::canManageMembers, "멤버 제거");

            assertThat(caller.getRole()).isEqualTo(OrgRole.OWNER);
        }

        @Test
        @DisplayName("역할이 모자라면 403 — 무슨 동작이 왜 막혔는지 메시지에 남는다")
        void denied() {
            when(loadMembership.findActiveMember(1L, 100L)).thenReturn(Optional.of(member(OrgRole.STAFF)));

            assertThatThrownBy(() ->
                    authorizer.requireRole(1L, 100L, OrgRole::canManageMembers, "멤버 제거"))
                    .isInstanceOf(ForbiddenOrgAccessException.class)
                    .hasMessageContaining("멤버 제거")
                    .hasMessageContaining("STAFF");
        }

        @Test
        @DisplayName("멤버가 아니면 역할 검사에 닿기도 전에 403")
        void notMemberFailsFirst() {
            when(loadMembership.findActiveMember(1L, 999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    authorizer.requireRole(1L, 999L, role -> true, "무엇이든"))
                    .isInstanceOf(ForbiddenOrgAccessException.class)
                    .hasMessageContaining("활성 멤버가 아닙니다");
        }
    }
}
