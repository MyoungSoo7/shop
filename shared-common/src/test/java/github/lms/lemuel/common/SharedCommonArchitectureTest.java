package github.lms.lemuel.common;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * shared-common 의 아키텍처 경계.
 *
 * <p><b>왜 이제야 생겼나</b> — 이 모듈은 ArchUnit 을 {@code testFixturesCompileOnly} 로만 갖고 있었다.
 * {@code InboundPortReachability} 픽스처를 <b>소비 서비스에 제공</b>하기 위한 것이라, order-service 와
 * operation-service 의 경계는 이 모듈이 만든 도구로 검사되는데 <b>정작 이 모듈 자신의 경계는 아무도
 * 검사하지 않는</b> 상태였다(2026-08-27 확인). 남의 자를 만들어 파는 집에 자가 없던 셈이다.
 * 그래서 여기에만 {@code testImplementation} 으로 ArchUnit 을 따로 얹는다.
 *
 * <p>대상은 헥사고날로 이미 갈라져 있는 두 기능이다 — {@code outbox}(Transactional Outbox)와
 * {@code audit}. 나머지({@code money}·{@code log}·{@code ratelimit} 등)는 계층 구조가 아니라
 * 유틸이라 계층 규칙을 걸 대상이 아니다.
 *
 * <p>여기에 <b>없는</b> 규칙 하나를 적어 둔다: 기능 간 순환 검사는 걸지 않았다. 이 모듈의 최상위
 * 슬라이스는 {@code common} 하나뿐이라 그래프에 노드가 하나고, 규칙은 자명하게 통과한다 —
 * 통과하는 규칙이 아니라 <i>아무것도 안 하는</i> 규칙이다.
 */
class SharedCommonArchitectureTest {

    private static final String ROOT_PACKAGE = "github.lms.lemuel.common.";

    /**
     * 임포트 건수 하한. shared-common main 은 현재 115개 클래스다.
     *
     * <p>아래 규칙은 전부 {@code allowEmptyShould(true)} 라서 임포트가 0개면 모두 green 이 된다.
     * order-service 가 ArchUnit 1.3.0 + Java 25(class major 69) 조합에서 실제로 0개를 임포트한 채
     * 규칙 전부 green 이었던 전례가 있다. 규칙보다 먼저 검사 대상 자체를 못 박는다.
     */
    private static final int MIN_IMPORTED_CLASSES = 80;

    private static JavaClasses commonClasses;

    @BeforeAll
    static void importClasses() {
        commonClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption((Location location) ->
                        !location.contains("/generated/") && !location.contains("/build/generated"))
                .importPackages("github.lms.lemuel.common");
    }

    @Test
    void 검사대상이_공허하지_않다() {
        assertTrue(commonClasses.size() >= MIN_IMPORTED_CLASSES,
                "아키텍처 규칙의 검사 대상이 " + commonClasses.size() + "개다 (기대 최소 "
                        + MIN_IMPORTED_CLASSES + "개). ArchUnit 이 현재 바이트코드 버전을 읽지 못하면 "
                        + "0개를 임포트하고 이 클래스의 모든 규칙이 공허 통과한다.");
    }

    @Test
    void 도메인은_application_과_adapter_와_config_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..common..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..common..application..", "..common..adapter..", "..common..config..")
                .because("도메인은 자기를 쓰는 쪽을 알지 않는다")
                .allowEmptyShould(true);
        rule.check(commonClasses);
    }

    @Test
    void 도메인은_스프링과_JPA_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..common..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "javax.persistence..")
                .because("도메인 레이어는 프레임워크에 의존하지 않는 순수 POJO 여야 한다")
                .allowEmptyShould(true);
        rule.check(commonClasses);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..common..application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..common..adapter..")
                .because("의존 방향은 adapter → application 한 쪽이다")
                .allowEmptyShould(true);
        rule.check(commonClasses);
    }

    /**
     * shared-common 은 <b>어떤 서비스의 도메인도 모른다</b>.
     *
     * <p>이 모듈의 규칙 중 가장 중요한 것이다. 여기가 뚫리면 라이브러리가 특정 서비스를 알게 되고,
     * 그 순간 "공용"이라는 말이 거짓이 된다 — 그 서비스를 빌드하지 않으면 라이브러리가 컴파일되지
     * 않는 상태가 되기 때문이다. 위 계층 규칙들과 달리 이건 리팩터로 갚을 부채가 아니라
     * <b>절대 생기면 안 되는</b> 방향이라 허용 목록을 두지 않는다.
     */
    @Test
    void shared_common_은_서비스_도메인에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.operation..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.organization..",
                        "github.lms.lemuel.payment..",
                        "github.lms.lemuel.product..",
                        "github.lms.lemuel.user..")
                .because("공용 라이브러리가 특정 서비스를 알면 그 순간 공용이 아니다")
                .allowEmptyShould(true);
        rule.check(commonClasses);
    }

    @Test
    void 포트는_인터페이스다() {
        ArchRule rule = classes()
                .that().resideInAPackage("..common..application.port..")
                .and().haveSimpleNameEndingWith("Port")
                .should().beInterfaces()
                .because("포트는 어댑터가 구현하는 계약 인터페이스")
                .allowEmptyShould(true);
        rule.check(commonClasses);
    }
}
