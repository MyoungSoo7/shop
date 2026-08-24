package github.lms.lemuel.operation;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * operation-service 의 헥사고날 아키텍처 + MSA 코드 경계를 강제하는 가드 (loan-service 패턴).
 *
 * <p>핵심 불변식:
 * <ul>
 *   <li>도메인은 application/adapter/config 에 의존하지 않는다 (의존 방향).</li>
 *   <li>application 은 adapter 에 의존하지 않는다 (config 의 OpsProperties 주입은 허용 —
 *       config 는 조립 계층이지 adapter 가 아니다).</li>
 *   <li>★ operation-service 는 order/settlement/loan 패키지에 코드 의존 0
 *       — 신호는 Alertmanager webhook(Phase 1)·Kafka 이벤트(Phase 2)로만 수신한다.</li>
 * </ul>
 */
class OperationArchitectureTest {

    private static JavaClasses operationClasses;

    @BeforeAll
    static void importClasses() {
        operationClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.operation");
    }

    @Test
    void 도메인은_application_과_adapter_와_config_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..operation..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..operation..application..", "..operation..adapter..", "..operation.config..")
                .allowEmptyShould(true);
        rule.check(operationClasses);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..operation..application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..operation..adapter..")
                .allowEmptyShould(true);
        rule.check(operationClasses);
    }

    @Test
    void operation_은_order_settlement_loan_에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.operation..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.loan..")
                .allowEmptyShould(true);
        rule.check(operationClasses);
    }

    /**
     * 슬라이스(= {@code github.lms.lemuel.<도메인>} 최상위 패키지) 사이에 순환 의존이 없다.
     *
     * <p>이 모듈은 현재 슬라이스가 1개뿐이라 규칙은 자명하게 통과한다. 그럼에도 켜 두는 이유는
     * <b>두 번째 최상위 도메인 패키지가 추가되는 순간</b>부터 순환을 차단하기 위해서다
     * (settlement-service 8건 · order-service 1건이 그렇게 쌓였다).
     *
     * <p>임포트 범위를 모듈 패키지가 아니라 {@code github.lms.lemuel} 로 넓히는 것이 핵심이다 —
     * 모듈 패키지로 좁히면 새로 생긴 형제 패키지가 애초에 임포트되지 않아 규칙이 못 본다.
     */
    @Test
    void 슬라이스_사이에_순환_의존이_없다() {
        JavaClasses lemuelClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel");
        // 임포터가 0개를 읽으면 순환 규칙은 조용히 통과한다(가짜 GREEN). 규칙보다 먼저 못 박는다.
        if (lemuelClasses.stream().findAny().isEmpty()) {
            throw new AssertionError("ArchUnit 임포터가 클래스를 0개 읽었다 — 순환 규칙이 무력화된다");
        }
        SlicesRuleDefinition.slices()
                .matching("github.lms.lemuel.(*)..")
                .should().beFreeOfCycles()
                .check(lemuelClasses);
    }
}
