package github.lms.lemuel.operation.education;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * education-service 의 헥사고날 경계 + MSA 코드 경계 가드 (deposit/card/organization 패턴).
 *
 * <p>education 은 18개 서비스 중 <b>유일하게 아키텍처 테스트가 없던 모듈</b>이었고, 실제로
 * 저장소 전체에서 유일한 "포트가 어댑터를 의존하는" 위반이 여기서 나왔다. 규칙이 지켜진 게 아니라
 * 검사가 있는 곳에서만 지켜졌다는 뜻이라, 본 수정보다 가드를 먼저 세운다.
 *
 * <p>가드를 세울 당시 위반 41건(application→adapter 40 · 포트 시그니처 1)은 동결 저장소에
 * 기록해 두고 시작했으나, 후속 커밋에서 리포지토리를 아웃바운드 포트로 승격하며 전부 해소돼
 * 동결 장치를 걷어냈다. 지금은 다른 17개 서비스와 같은 평범한 규칙이다 — 예외 목록이 없다.
 */
class EducationArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.operation.education");
    }

    @Test
    void 도메인은_application_adapter_config_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..education.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..education.application..", "..education.adapter..", "..education.config..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void 도메인은_JPA_와_Spring_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..education.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.springframework..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..education.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..education.adapter..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    /**
     * 포트는 의도만 담고 기술을 담지 않는다.
     *
     * <p>포트 시그니처에 JPA 엔티티·{@code Pageable}·서블릿·Kafka 타입이 올라오면 코드 의존성이
     * 안에서 밖으로 뒤집히고, 어댑터를 갈아끼울 때 애플리케이션까지 따라 바뀐다.
     */
    @Test
    void 포트는_기술_타입을_시그니처에_노출하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..education.application.port..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..education.adapter..",
                        "jakarta.persistence..",
                        "jakarta.servlet..",
                        "org.springframework..",
                        "org.apache.kafka..",
                        "com.fasterxml.jackson..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void education_은_타_서비스_도메인에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.operation.education..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.card..",
                        "github.lms.lemuel.loan..",
                        "github.lms.lemuel.investment..",
                        "github.lms.lemuel.account..",
                        "github.lms.lemuel.organization..",
                        "github.lms.lemuel.deposit..",
                        "github.lms.lemuel.company..")
                .allowEmptyShould(true);
        rule.check(classes);
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
