package github.lms.lemuel.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역할 서열·슬롯 점유·재수화(rehydrate) 경로.
 *
 * <p>권한 판정이 이 세 술어({@code atLeast}·{@code canInviteMembers}·{@code canManageMembers})
 * 위에 서 있고, 영속 계층은 빌더로 도메인을 되살린다. 되살린 값이 저장된 값과 다르면
 * 낙관적 락(version)이나 감사 기록(createdAt)이 조용히 어긋난다.
 */
class OrgRoleAndAccessorsTest {

    @Nested
    @DisplayName("역할 서열")
    class Ranking {

        @Test
        @DisplayName("OWNER > MANAGER > STAFF")
        void ordering() {
            assertThat(OrgRole.OWNER.atLeast(OrgRole.MANAGER)).isTrue();
            assertThat(OrgRole.MANAGER.atLeast(OrgRole.STAFF)).isTrue();
            assertThat(OrgRole.STAFF.atLeast(OrgRole.MANAGER)).isFalse();
            assertThat(OrgRole.MANAGER.atLeast(OrgRole.OWNER)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(OrgRole.class)
        @DisplayName("자기 자신 이상은 항상 참")
        void reflexive(OrgRole role) {
            assertThat(role.atLeast(role)).isTrue();
        }

        @Test
        @DisplayName("초대는 OWNER·MANAGER, 멤버 관리는 OWNER 전용")
        void permissions() {
            assertThat(OrgRole.OWNER.canInviteMembers()).isTrue();
            assertThat(OrgRole.MANAGER.canInviteMembers()).isTrue();
            assertThat(OrgRole.STAFF.canInviteMembers()).isFalse();

            assertThat(OrgRole.OWNER.canManageMembers()).isTrue();
            assertThat(OrgRole.MANAGER.canManageMembers()).isFalse();
            assertThat(OrgRole.STAFF.canManageMembers()).isFalse();
        }
    }

    @Nested
    @DisplayName("멤버십 상태")
    class Status {

        @Test
        @DisplayName("INVITED·ACTIVE 만 활성 슬롯을 점유한다")
        void occupiesActiveSlot() {
            // 이 술어는 부분 UNIQUE 인덱스(uq_membership_active)와 짝을 이룬다. 둘이 어긋나면
            // 같은 (조직,사용자)로 초대가 두 번 성립하거나, 반대로 재초대가 영영 막힌다.
            assertThat(MembershipStatus.INVITED.occupiesActiveSlot()).isTrue();
            assertThat(MembershipStatus.ACTIVE.occupiesActiveSlot()).isTrue();
            for (MembershipStatus status : MembershipStatus.values()) {
                if (status != MembershipStatus.INVITED && status != MembershipStatus.ACTIVE) {
                    assertThat(status.occupiesActiveSlot())
                            .as("%s 는 슬롯을 비워야 재초대가 가능하다", status)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("isActive 는 ACTIVE 하나뿐")
        void isActive() {
            assertThat(MembershipStatus.ACTIVE.isActive()).isTrue();
            assertThat(MembershipStatus.INVITED.isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("영속 계층에서의 재수화")
    class Rehydration {

        private static final Instant CREATED = Instant.parse("2026-08-22T01:02:03Z");

        @Test
        @DisplayName("조직은 저장된 감사 필드·버전 그대로 되살아난다")
        void organization() {
            Organization org = Organization.builder()
                    .id(7L)
                    .name("르뮤엘 셀러")
                    .type(OrganizationType.SELLER)
                    .externalRef("SELLER-1")
                    .status(OrganizationStatus.ACTIVE)
                    .createdAt(CREATED)
                    .version(4L)
                    .build();

            assertThat(org.getId()).isEqualTo(7L);
            assertThat(org.getExternalRef()).isEqualTo("SELLER-1");
            assertThat(org.getCreatedAt()).isEqualTo(CREATED);
            // version 이 유실되면 낙관적 락이 매번 0 으로 덮어써 갱신 충돌을 못 잡는다.
            assertThat(org.getVersion()).isEqualTo(4L);
        }

        @Test
        @DisplayName("멤버십도 초대자·감사 필드·버전 그대로 되살아난다")
        void membership() {
            Membership membership = Membership.builder()
                    .id(11L)
                    .organizationId(7L)
                    .userId(100L)
                    .role(OrgRole.MANAGER)
                    .status(MembershipStatus.ACTIVE)
                    .invitedBy(1L)
                    .createdAt(CREATED)
                    .version(2L)
                    .build();

            assertThat(membership.getId()).isEqualTo(11L);
            assertThat(membership.getInvitedBy()).isEqualTo(1L);
            assertThat(membership.getCreatedAt()).isEqualTo(CREATED);
            assertThat(membership.getVersion()).isEqualTo(2L);
        }
    }
}
