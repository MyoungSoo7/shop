package github.lms.lemuel.organization;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * organization-service 의 헥사고날 아키텍처 + MSA 코드 경계를 강제하는 가드(investment/operation 패턴).
 *
 * <ul>
 *   <li>도메인은 application/adapter/config 에 의존하지 않는다(의존 방향).</li>
 *   <li>application 은 adapter 에 의존하지 않는다.</li>
 *   <li>★ organization-service 는 타 서비스 도메인 패키지에 코드 의존 0 — 연계는 Kafka 이벤트 발행으로만.</li>
 * </ul>
 */
class OrganizationArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.organization");
    }

    @Test
    void 도메인은_application_adapter_config_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..organization.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..organization.application..", "..organization.adapter..", "..organization.config..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..organization.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..organization.adapter..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void organization_은_타_서비스_도메인에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.organization..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.loan..",
                        "github.lms.lemuel.investment..",
                        "github.lms.lemuel.account..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    // 슬라이스 순환 규칙은 여기 두지 않는다 — order-service 의
    // HexagonalArchitectureTest.슬라이스_사이에_순환_의존이_없다() 가 같은 매처
    // (github.lms.lemuel.(*)..) 로 같은 모듈 전체를 이미 검사한다. organization 이
    // 독립 서비스였을 때는 여기가 유일한 검사였지만, order 로 이관(ADR 0042)한 뒤로는
    // 전 클래스 재임포트를 두 번 하는 중복일 뿐이다.
}
