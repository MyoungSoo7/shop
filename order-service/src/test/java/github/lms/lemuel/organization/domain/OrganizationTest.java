package github.lms.lemuel.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationTest {

    @Test
    @DisplayName("생성 시 ACTIVE 로 시작한다")
    void create_startsActive() {
        Organization org = Organization.create("무신사", OrganizationType.SELLER, "123456");

        assertThat(org.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(org.getType()).isEqualTo(OrganizationType.SELLER);
        assertThat(org.getExternalRef()).isEqualTo("123456");
    }

    @Test
    @DisplayName("ACTIVE ⇄ SUSPENDED 전이가 가능하다")
    void suspendAndActivate() {
        Organization org = Organization.create("셀러", OrganizationType.SELLER, null);

        org.suspend();
        assertThat(org.getStatus()).isEqualTo(OrganizationStatus.SUSPENDED);

        org.activate();
        assertThat(org.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
    }

    @Test
    @DisplayName("이미 SUSPENDED 인데 다시 suspend 하면 상태머신 위반")
    void doubleSuspend_isRejected() {
        Organization org = Organization.create("셀러", OrganizationType.SELLER, null);
        org.suspend();

        assertThatThrownBy(org::suspend)
                .isInstanceOf(InvalidOrganizationTransitionException.class);
    }

    @Test
    @DisplayName("이미 ACTIVE 인데 다시 activate 하면 상태머신 위반")
    void doubleActivate_isRejected() {
        Organization org = Organization.create("셀러", OrganizationType.SELLER, null);

        assertThatThrownBy(org::activate)
                .isInstanceOf(InvalidOrganizationTransitionException.class);
    }
}
