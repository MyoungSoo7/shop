package github.lms.lemuel.marketing;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * marketing-service 의 헥사고날 경계와 MSA 경계를 강제한다.
 *
 * <p>불변식은 다섯이다.
 * <ul>
 *   <li>도메인은 application·adapter·config 를 모른다.</li>
 *   <li>application 은 adapter 를 모른다.</li>
 *   <li>인바운드 어댑터는 {@code port.in} 만 경유한다 — 구체 서비스를 직접 물지 않는다.</li>
 *   <li>application 은 저장소 기술(JPA·스프링 데이터·Hibernate)을 모른다.</li>
 *   <li>★ marketing 은 order·settlement·loan·operation 패키지에 <b>코드 의존이 0</b>이다 —
 *       포인트 적립은 Kafka 이벤트로만 요청하고 결과도 이벤트로만 받는다.</li>
 * </ul>
 *
 * <p>마지막 규칙이 이 서비스의 존재 이유와 직결된다. 출석·럭키박스는 결국 포인트를 주는 기능이고,
 * 포인트 원장은 order-service 에 있다. 여기서 order 의 타입을 하나라도 임포트하는 순간
 * "서비스 간 연계는 Kafka 이벤트로만" 이라는 리포 전체의 불변식이 깨지고, 두 서비스는 따로 배포할
 * 수 없는 한 덩어리가 된다. 손이 가장 가는 지름길이라 규칙으로 막는다.
 *
 * <p><b>허용 목록이 없다.</b> 이 모듈은 신규라 첫날부터 깨끗하므로 래칫이 필요 없다. 위반이 생기면
 * 목록을 만들지 말고 코드를 고쳐라 — 목록이 생기는 순간 "예외가 있는 규칙" 이 되고, 그다음부터는
 * 그 예외가 원래 있던 것인지 새로 판 구멍인지 아무도 구분하지 못한다.
 */
class MarketingArchitectureTest {

    /**
     * 임포트 건수 하한.
     *
     * <p>이 검사가 왜 있냐면, ArchUnit 이 현재 바이트코드 버전을 못 읽으면 클래스를 <b>0개</b>
     * 임포트하고 아래 규칙 전부가 "검사 대상 없음" 으로 조용히 통과하기 때문이다. 같은 리포의
     * order-service 가 ArchUnit 1.3.0 + Java 25(class major 69) 조합에서 정확히 그 상태로
     * 초록불이었다. 초록불과 눈먼 상태는 겉으로 구분되지 않는다.
     */
    private static final int MIN_IMPORTED_CLASSES = 60;

    private static final Set<String> PERSISTENCE_TECH_PACKAGES = Set.of(
            "org.springframework.dao", "org.springframework.data",
            "jakarta.persistence", "javax.persistence", "org.hibernate");

    private static JavaClasses marketingClasses;

    @BeforeAll
    static void importClasses() {
        marketingClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption((Location location) ->
                        !location.contains("/generated/") && !location.contains("/build/generated"))
                .importPackages("github.lms.lemuel.marketing");
    }

    @Test
    void 검사대상이_공허하지_않다() {
        assertTrue(marketingClasses.size() >= MIN_IMPORTED_CLASSES,
                "아키텍처 규칙의 검사 대상이 " + marketingClasses.size() + "개다 (기대 최소 "
                        + MIN_IMPORTED_CLASSES + "개). ArchUnit 이 현재 바이트코드 버전을 읽지 못하면 "
                        + "0개를 임포트하고 이 클래스의 모든 규칙이 공허 통과한다.");
    }

    @Test
    void 도메인은_application_과_adapter_와_config_를_모른다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..marketing.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..marketing.application..", "..marketing.adapter..", "..marketing.config..")
                .because("도메인은 규칙만 안다 — 어떻게 저장되고 어떻게 호출되는지는 도메인의 관심사가 아니다");
        rule.check(marketingClasses);
    }

    @Test
    void application_은_adapter_를_모른다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..marketing.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..marketing.adapter..")
                .because("유스케이스는 포트로만 말한다");
        rule.check(marketingClasses);
    }

    @Test
    void 인바운드_어댑터는_구체_서비스를_직접_물지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..marketing.adapter.in..")
                .should().dependOnClassesThat()
                .resideInAPackage("..marketing.application.service..")
                .because("컨트롤러·컨슈머·스케줄러는 port.in 만 경유한다 (구체 서비스·그 중첩 타입 금지)");
        rule.check(marketingClasses);
    }

    /**
     * 유스케이스는 저장소 기술을 모른다.
     *
     * <p>{@code dependOnClassesThat()} 만으로 쓰면 안 된다 — ArchUnit 의
     * {@code getDirectDependenciesFromSelf()} 는 <b>catch 절의 예외 타입을 포함하지 않는다</b>.
     * 유스케이스가 저장소 기술에 물드는 가장 흔한 형태가 바로
     * {@code catch (DataIntegrityViolationException e)} 라서, DSL 로만 쓰면 규칙이 그 한 가지를
     * 정확히 못 본 채 초록불이 된다. {@code getTryCatchBlocks()} 로 catch 절까지 훑는다.
     */
    @Test
    void application_은_저장소_기술을_모른다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..marketing.application..")
                .should(dependOnPersistenceTechnology())
                .because("@Service·@Transactional 은 실용적 타협으로 허용하되 저장소 기술은 어댑터에 가둔다");
        rule.check(marketingClasses);
    }

    @Test
    void marketing_은_다른_서비스_패키지에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.marketing..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.point..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.loan..",
                        "github.lms.lemuel.operation..")
                .because("포인트 적립은 Kafka 이벤트로만 요청한다 — 코드로 물면 따로 배포할 수 없다");
        rule.check(marketingClasses);
    }

    // ---------------------------------------------------------------- helpers

    private static ArchCondition<JavaClass> dependOnPersistenceTechnology() {
        return new ArchCondition<>("저장소 기술 타입에 의존") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (String target : persistenceTechTargets(clazz)) {
                    events.add(SimpleConditionEvent.satisfied(clazz, clazz.getName() + " → " + target));
                }
            }
        };
    }

    private static boolean isPersistenceTech(String className) {
        return PERSISTENCE_TECH_PACKAGES.stream().anyMatch(pkg -> className.startsWith(pkg + "."));
    }

    private static Set<String> persistenceTechTargets(JavaClass clazz) {
        Set<String> hits = new TreeSet<>();
        clazz.getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getName())
                .filter(MarketingArchitectureTest::isPersistenceTech)
                .forEach(hits::add);
        for (JavaCodeUnit codeUnit : clazz.getCodeUnits()) {
            for (TryCatchBlock block : codeUnit.getTryCatchBlocks()) {
                block.getCaughtThrowables().stream()
                        .map(JavaClass::getName)
                        .filter(MarketingArchitectureTest::isPersistenceTech)
                        .forEach(hits::add);
            }
        }
        return hits;
    }
}
