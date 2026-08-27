package github.lms.lemuel.organization;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * 임포트 건수 하한. organization 은 현재 39개 파일이다.
     *
     * <p>이 클래스의 규칙 셋은 전부 {@code allowEmptyShould(true)} 라서, 임포트가 0개면 <b>세 개 모두</b>
     * 검사 대상 없이 green 이 된다 — 특히 세 번째(타 서비스 도메인 의존 0)는 organization 이 통째로
     * 사라져도 통과한다. green 과 blind 는 겉으로 구분되지 않으므로 건수를 먼저 못 박는다.
     * order-service 는 실제로 ArchUnit 1.3.0 + Java 25 조합에서 0개를 임포트한 적이 있다
     * ({@code HexagonalArchitectureTest.importedClassesMustNotBeVacuous()} 주석 참조).
     */
    private static final int MIN_IMPORTED_CLASSES = 20;

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.organization");
    }

    @Test
    void 검사대상이_공허하지_않다() {
        assertTrue(classes.size() >= MIN_IMPORTED_CLASSES,
                "아키텍처 규칙의 검사 대상이 " + classes.size() + "개다 (기대 최소 "
                        + MIN_IMPORTED_CLASSES + "개). 임포트가 0개면 이 클래스의 규칙 세 개가 전부 공허 통과한다.");
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
