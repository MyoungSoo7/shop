package github.lms.lemuel.operation.board;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 헥사고날 의존 방향 강제.
 *
 * <p>문서로 적어 둔 경계는 지켜지지 않는다 — 컴파일이 잡아 주지 않기 때문이다. 도메인이 어댑터를
 * 한 번만 import 해도 "정책을 바꾸려면 JPA 를 알아야 하는" 구조로 미끄러진다.
 */
class BoardArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.operation.board");
    }

    @Test
    @DisplayName("도메인은 어댑터를 모른다")
    void domainDoesNotDependOnAdapter() {
        noClasses().that().resideInAPackage("..board.domain..")
                .should().dependOnClassesThat().resideInAPackage("..board.adapter..")
                .check(classes);
    }

    @Test
    @DisplayName("도메인은 응용 계층을 모른다 — 의존은 안에서 밖이 아니라 밖에서 안으로 흐른다")
    void domainDoesNotDependOnApplication() {
        noClasses().that().resideInAPackage("..board.domain..")
                .should().dependOnClassesThat().resideInAPackage("..board.application..")
                .check(classes);
    }

    @Test
    @DisplayName("도메인은 스프링·JPA 를 모른다 — 순수 POJO")
    void domainIsFrameworkFree() {
        noClasses().that().resideInAPackage("..board.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "jakarta.validation..")
                .check(classes);
    }

    @Test
    @DisplayName("응용 계층은 어댑터를 모른다 — 포트를 통해서만 바깥과 만난다")
    void applicationDoesNotDependOnAdapter() {
        noClasses().that().resideInAPackage("..board.application..")
                .should().dependOnClassesThat().resideInAPackage("..board.adapter..")
                .check(classes);
    }

    @Test
    @DisplayName("영속 어댑터는 웹 어댑터를 모른다 — 두 바깥은 서로를 몰라야 교체가 가능하다")
    void persistenceDoesNotDependOnWeb() {
        noClasses().that().resideInAPackage("..board.adapter.out..")
                .should().dependOnClassesThat().resideInAPackage("..board.adapter.in..")
                .check(classes);
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
