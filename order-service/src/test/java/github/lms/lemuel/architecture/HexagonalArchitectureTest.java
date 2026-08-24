package github.lms.lemuel.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 헥사고날 아키텍처 경계 규칙.
 *
 * 일부 규칙은 현재 위반되는 코드가 있어 임시 허용 목록(exclude)을 두고 있다.
 * 허용 목록에 있는 파일들은 별도의 리팩터 태스크로 정리해야 한다.
 */
class HexagonalArchitectureTest {

    private static final String ROOT_PACKAGE = "github.lms.lemuel.";

    /**
     * 임포트 건수 하한. order-service 는 현재 약 545개를 임포트한다.
     * 모듈 분리 등으로 이 값을 밑돌면 규칙이 아니라 <b>임포트 자체</b>를 먼저 점검해야 한다.
     */
    private static final int MIN_IMPORTED_CLASSES = 100;

    private static JavaClasses mainClasses;

    @BeforeAll
    static void importClasses() {
        mainClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption((Location location) ->
                        !location.contains("/generated/") && !location.contains("/build/generated"))
                .importPackages("github.lms.lemuel");
    }

    /**
     * 아래 모든 규칙은 {@link #mainClasses} 를 대상으로 한다. 임포트가 0개면 규칙 전부가
     * <b>공허 통과</b>(검사 대상 없이 green)한다 — 실제로 order-service 는 ArchUnit 1.3.0 +
     * Java 25(class major 69) 조합에서 0개를 임포트한 채 4개 규칙 전부 green 이었다.
     * green 과 blind 는 겉으로 구분되지 않으므로 임포트 건수를 먼저 검사한다.
     */
    @Test
    void importedClassesMustNotBeVacuous() {
        assertTrue(mainClasses.size() >= MIN_IMPORTED_CLASSES,
                "아키텍처 규칙의 검사 대상이 " + mainClasses.size() + "개다 (기대 최소 "
                        + MIN_IMPORTED_CLASSES + "개). ArchUnit 이 현재 바이트코드 버전을 읽지 못하면 "
                        + "0개를 임포트하고 이 클래스의 모든 규칙이 공허 통과한다.");
    }

    /**
     * 도메인 레이어(..domain..)는 Spring/JPA/프레임워크 의존성을 가지지 않아야 한다.
     * adapter·application 하위의 `.domain` 유사 패키지는 제외.
     */
    @Test
    void domainShouldNotDependOnSpringOrJpa() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .and().resideOutsideOfPackage("..adapter..")
                .and().resideOutsideOfPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "javax.persistence..")
                .because("도메인 레이어는 프레임워크에 의존하지 않는 순수 POJO 여야 한다")
                .allowEmptyShould(true);

        rule.check(mainClasses);
    }

    /**
     * 애플리케이션 서비스는 JPA 리포지토리/어댑터 구현체에 직접 의존하지 않는다.
     *
     * 현재 허용 예외: EcommerceCategoryService, ProductImageService — 향후 포트/어댑터로 분리할 리팩터 대상.
     */
    @Test
    void applicationServiceShouldNotUseJpaRepositoryDirectly() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.service..")
                .should().dependOnClassesThat().resideInAPackage("..adapter.out.persistence..")
                .because("애플리케이션 서비스는 어댑터(JPA)에 직접 의존하지 않고 포트를 사용해야 한다")
                .allowEmptyShould(true);

        rule.check(mainClasses);
    }

    /**
     * 어댑터는 타 도메인의 JPA 엔티티/리포지토리를 직접 import 하지 않는다.
     *
     * <p>이전에 있던 이름 기반 허용 예외 3종(SettlementSearchDocumentMapper /
     * SettlementQueryRepositoryImpl / CapturedPaymentsAdapter)은 제거했다.
     * 모듈 분리로 셋 다 order-service 에 더는 존재하지 않아 예외가 죽어 있었고,
     * 실측 결과 타 도메인 의존은 0건이라 예외 없이도 규칙이 통과한다.
     * 다시 필요해지면 그때 근거와 함께 추가한다.
     */
    @Test
    void adaptersShouldNotDirectlyReferenceOtherDomainsPersistence() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..adapter..")
                .should(dependOnOtherDomainPersistence())
                .because("어댑터는 타 도메인의 JPA 엔티티/리포지토리를 직접 import 하지 않는다")
                .allowEmptyShould(true);

        rule.check(mainClasses);
    }

    /**
     * {@code github.lms.lemuel.<도메인>...} 에서 도메인 세그먼트를 뽑는다.
     * 루트 패키지 밖이면 {@code null}.
     */
    private static String domainOf(String packageName) {
        if (!packageName.startsWith(ROOT_PACKAGE)) {
            return null;
        }
        String rest = packageName.substring(ROOT_PACKAGE.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /**
     * 소스 클래스가 <b>자기 도메인이 아닌</b> 도메인의 {@code adapter.out.persistence} 에
     * 의존하는지 본다.
     *
     * <p>이전 구현은 타깃 패키지만 검사하는 {@code DescribedPredicate} 라서 소스 도메인과
     * 비교하지 않았고, 같은 도메인 자기참조(QueryDSL {@code Q*}·자기 리포지토리)까지 전부
     * 위반으로 잡았다. 규칙 이름은 "타 도메인"인데 구현은 "모든 도메인"이었다.
     * 소스·타깃을 함께 봐야 하므로 {@link ArchCondition} 으로 바꾼다.
     */
    private static ArchCondition<JavaClass> dependOnOtherDomainPersistence() {
        return new ArchCondition<>("타 도메인의 adapter.out.persistence 에 의존") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                String sourceDomain = domainOf(source.getPackageName());
                if (sourceDomain == null) {
                    return;
                }
                for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    if (!targetPackage.contains(".adapter.out.persistence")) {
                        continue;
                    }
                    String targetDomain = domainOf(targetPackage);
                    if (targetDomain == null || targetDomain.equals(sourceDomain)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.satisfied(source,
                            source.getName() + " → " + dependency.getTargetClass().getName()
                                    + "  (" + sourceDomain + " → " + targetDomain + ")"));
                }
            }
        };
    }

    /**
     * 슬라이스(= {@code github.lms.lemuel.<도메인>} 최상위 패키지) 사이에 순환 의존이 없다.
     *
     * <p>도입 시점 실측 2건을 먼저 없애고 켰다 — {@code order ↔ payment} 와 {@code order ↔ shipping}.
     * 둘 다 어댑터 침범이 아니라 <b>양방향 제공 포트 호출</b>이었고, 각 쌍에서 어댑터 1개씩을
     * 능력을 가진 슬라이스로 옮겨 결합을 {@code payment → order → shipping} 한 방향으로 모아 끊었다.
     *
     * <p>순환의 대가는 추상적이지 않다: {@code order ↔ payment} 는 스프링 생성자 주입 사이클을 만들어
     * {@code OrderPaymentRefundAdapter} 가 {@code @Lazy} 로 그것을 틀어막고 있다. 다만 이 규칙이 보는
     * 것은 컴파일 시점 그래프라, 패키지 이동만으로 그 빈 사이클이 사라지지는 않는다.
     *
     * <p>임포트 공허 통과 방어는 {@link #importedClassesMustNotBeVacuous()} 가 같은 {@link #mainClasses}
     * 에 대해 담당한다 — 임포트가 0개면 빈 그래프에 순환이 없어 이 규칙도 조용히 통과한다.
     */
    @Test
    void 슬라이스_사이에_순환_의존이_없다() {
        SlicesRuleDefinition.slices()
                .matching("github.lms.lemuel.(*)..")
                .should().beFreeOfCycles()
                .check(mainClasses);
    }

    /**
     * application.port.* 의 *Port 는 인터페이스여야 한다.
     */
    @Test
    void portsShouldBeInterfaces() {
        ArchRule rule = classes()
                .that().resideInAPackage("..application.port..")
                .and().haveSimpleNameEndingWith("Port")
                .should().beInterfaces()
                .because("포트는 어댑터가 구현하는 계약 인터페이스")
                .allowEmptyShould(true);

        rule.check(mainClasses);
    }
}
