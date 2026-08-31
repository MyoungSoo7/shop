package github.lms.lemuel.seller;

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
 * seller-service 의 헥사고날 경계·MSA 경계를 강제한다.
 *
 * <ul>
 *   <li>도메인은 application·adapter·config 를 모른다.</li>
 *   <li>application 은 adapter 를 모른다.</li>
 *   <li>인바운드 어댑터는 {@code port.in} 만 경유한다.</li>
 *   <li>application 은 저장소 기술(JPA·스프링 데이터·Hibernate)을 모른다.</li>
 *   <li>seller 는 order·point·settlement·loan·operation·marketing·partner 패키지에 코드 의존이 0이다.</li>
 * </ul>
 *
 * <h2>partner-service 에 있던 일곱 번째 규칙이 여기엔 없다</h2>
 * 파트너 콘솔에는 "웹 어댑터에 쓰기 매핑이 하나도 없다" 는 규칙이 있다. 그 서비스는 어떤 사실의
 * 원본도 갖지 않는 순수 읽기 프로젝션이라, {@code @PostMapping} 한 줄이 곧 원본 이중화였다.
 *
 * <p>이 서비스는 다르다. 상품 등록 신청서({@code product_submissions})의 <b>원본을 소유한다</b> —
 * 셀러가 여기서 직접 만들고 고치고 제출한다. 그래서 쓰기 매핑이 있는 것이 정상이고, 같은 규칙을
 * 복사해 오면 첫날부터 실패한다.
 *
 * <p>다만 <b>그 하나만</b> 원본이라는 사실은 규칙으로 지킬 수 없다. 상품 카탈로그와 주문 원장은
 * 여전히 order-service 소유이고, 이 서비스의 {@code seller_products}·{@code seller_orders} 는
 * 이벤트 사본이다. 사본에 직접 쓰는 컨트롤러가 생기면 다음 이벤트가 조용히 덮는다 — ArchUnit 이
 * 볼 수 있는 형태가 아니므로, 그 경계는 아래 마지막 규칙(다른 서비스 패키지 무의존)과
 * {@code SellerProductController} 의 주석이 대신 지킨다.
 *
 * <p><b>허용 목록이 없다.</b> 신규 모듈이라 첫날부터 깨끗하다. 위반이 생기면 목록을 만들지 말고
 * 코드를 고쳐라 — 목록이 생기는 순간 "예외가 있는 규칙" 이 되고, 그다음부터는 그 예외가 원래
 * 있던 것인지 새로 판 구멍인지 아무도 구분하지 못한다.
 */
class SellerArchitectureTest {

    /**
     * 임포트 건수 하한.
     *
     * <p>ArchUnit 이 현재 바이트코드 버전을 못 읽으면 클래스를 <b>0개</b> 임포트하고 아래 규칙
     * 전부가 "검사 대상 없음" 으로 조용히 통과한다. 같은 리포의 order-service 가
     * ArchUnit 1.3.0 + Java 25(class major 69) 조합에서 정확히 그 상태로 초록불이었다.
     * 초록불과 눈먼 상태는 겉으로 구분되지 않는다.
     */
    private static final int MIN_IMPORTED_CLASSES = 60;

    private static final Set<String> PERSISTENCE_TECH_PACKAGES = Set.of(
            "org.springframework.dao", "org.springframework.data",
            "jakarta.persistence", "javax.persistence", "org.hibernate");

    private static JavaClasses sellerClasses;

    @BeforeAll
    static void importClasses() {
        sellerClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption((Location location) ->
                        !location.contains("/generated/") && !location.contains("/build/generated"))
                .importPackages("github.lms.lemuel.seller");
    }

    @Test
    void 검사대상이_공허하지_않다() {
        assertTrue(sellerClasses.size() >= MIN_IMPORTED_CLASSES,
                "아키텍처 규칙의 검사 대상이 " + sellerClasses.size() + "개다 (기대 최소 "
                        + MIN_IMPORTED_CLASSES + "개). ArchUnit 이 현재 바이트코드 버전을 읽지 못하면 "
                        + "0개를 임포트하고 이 클래스의 모든 규칙이 공허 통과한다.");
    }

    @Test
    void 도메인은_application_과_adapter_와_config_를_모른다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..seller.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..seller.application..", "..seller.adapter..", "..seller.config..")
                .because("도메인은 규칙만 안다 — 어떻게 저장되고 어떻게 호출되는지는 도메인의 관심사가 아니다");
        rule.check(sellerClasses);
    }

    @Test
    void application_은_adapter_를_모른다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..seller.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..seller.adapter..")
                .because("유스케이스는 포트로만 말한다");
        rule.check(sellerClasses);
    }

    @Test
    void 인바운드_어댑터는_구체_서비스를_직접_물지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..seller.adapter.in..")
                .should().dependOnClassesThat()
                .resideInAPackage("..seller.application.service..")
                .because("컨트롤러·컨슈머는 port.in 만 경유한다 (구체 서비스·그 중첩 타입 금지)");
        rule.check(sellerClasses);
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
                .that().resideInAPackage("..seller.application..")
                .should(dependOnPersistenceTechnology())
                .because("@Service·@Transactional 은 실용적 타협으로 허용하되 저장소 기술은 어댑터에 가둔다");
        rule.check(sellerClasses);
    }

    @Test
    void seller_는_다른_서비스_패키지에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.seller..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.point..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.loan..",
                        "github.lms.lemuel.operation..",
                        "github.lms.lemuel.marketing..",
                        "github.lms.lemuel.partner..")
                .because("셀러 콘솔은 Kafka 이벤트로만 사실을 주고받는다 — 코드로 물면 따로 배포할 수 없다. "
                        + "특히 승인된 상품을 카탈로그에 싣는 일은 order-service 를 직접 부르는 것이 "
                        + "훨씬 짧아 보이지만, 그 순간 상품 등록이 두 서비스의 동시 가동을 요구하게 된다");
        rule.check(sellerClasses);
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
                .filter(SellerArchitectureTest::isPersistenceTech)
                .forEach(hits::add);
        for (JavaCodeUnit codeUnit : clazz.getCodeUnits()) {
            for (TryCatchBlock block : codeUnit.getTryCatchBlocks()) {
                block.getCaughtThrowables().stream()
                        .map(JavaClass::getName)
                        .filter(SellerArchitectureTest::isPersistenceTech)
                        .forEach(hits::add);
            }
        }
        return hits;
    }
}
