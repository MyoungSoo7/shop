package github.lms.lemuel.partner;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.Set;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * partner-service 의 헥사고날 경계·MSA 경계, 그리고 이 서비스만의 불변식을 강제한다.
 *
 * <p>앞의 다섯은 marketing-service 와 같은 규칙이다. 여섯 번째가 이 서비스 고유다.
 * <ul>
 *   <li>도메인은 application·adapter·config 를 모른다.</li>
 *   <li>application 은 adapter 를 모른다.</li>
 *   <li>인바운드 어댑터는 {@code port.in} 만 경유한다.</li>
 *   <li>application 은 저장소 기술(JPA·스프링 데이터·Hibernate)을 모른다.</li>
 *   <li>partner 는 order·point·settlement·loan·operation·marketing 패키지에 코드 의존이 0이다.</li>
 *   <li>★ 웹 어댑터에 <b>쓰기 매핑이 하나도 없다</b> — 아래 참조.</li>
 * </ul>
 *
 * <h2>왜 쓰기 매핑을 규칙으로 막는가</h2>
 * partner-service 는 어떤 데이터의 원본도 소유하지 않는다. 여기 있는 일곱 테이블은 전부 다른
 * 서비스가 발행한 이벤트의 <b>사본</b>이고, 원본은 order-service 에 있다. 그런데 화면을 만들다
 * 보면 "조직명만 파트너가 직접 고치게 하자" 같은 요구가 반드시 한 번은 나온다. 그걸 여기서
 * 받으면 그 순간 같은 사실에 원본이 둘이 되고, 다음 {@code organization.created} 이벤트가
 * 도착하는 즉시 파트너가 고친 값이 조용히 덮인다 — 장애로 보이지도 않고 재현도 안 된다.
 *
 * <p>수정이 정말 필요하면 order-service 에 API 를 내고 이 서비스는 이벤트로 따라오면 된다.
 * 그 길이 멀어 보여서 지름길을 내는 것을 막는 게 이 규칙이다. 규칙이 없으면 리뷰에서
 * {@code @PutMapping} 한 줄은 반드시 통과한다.
 *
 * <p><b>허용 목록이 없다.</b> 신규 모듈이라 첫날부터 깨끗하다. 위반이 생기면 목록을 만들지 말고
 * 코드를 고쳐라 — 목록이 생기는 순간 "예외가 있는 규칙" 이 되고, 그다음부터는 그 예외가 원래
 * 있던 것인지 새로 판 구멍인지 아무도 구분하지 못한다.
 */
class PartnerArchitectureTest {

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

    private static JavaClasses partnerClasses;

    @BeforeAll
    static void importClasses() {
        partnerClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption((Location location) ->
                        !location.contains("/generated/") && !location.contains("/build/generated"))
                .importPackages("github.lms.lemuel.partner");
    }

    @Test
    void 검사대상이_공허하지_않다() {
        assertTrue(partnerClasses.size() >= MIN_IMPORTED_CLASSES,
                "아키텍처 규칙의 검사 대상이 " + partnerClasses.size() + "개다 (기대 최소 "
                        + MIN_IMPORTED_CLASSES + "개). ArchUnit 이 현재 바이트코드 버전을 읽지 못하면 "
                        + "0개를 임포트하고 이 클래스의 모든 규칙이 공허 통과한다.");
    }

    @Test
    void 도메인은_application_과_adapter_와_config_를_모른다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..partner.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..partner.application..", "..partner.adapter..", "..partner.config..")
                .because("도메인은 규칙만 안다 — 어떻게 저장되고 어떻게 호출되는지는 도메인의 관심사가 아니다");
        rule.check(partnerClasses);
    }

    @Test
    void application_은_adapter_를_모른다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..partner.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..partner.adapter..")
                .because("유스케이스는 포트로만 말한다");
        rule.check(partnerClasses);
    }

    @Test
    void 인바운드_어댑터는_구체_서비스를_직접_물지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..partner.adapter.in..")
                .should().dependOnClassesThat()
                .resideInAPackage("..partner.application.service..")
                .because("컨트롤러·컨슈머는 port.in 만 경유한다 (구체 서비스·그 중첩 타입 금지)");
        rule.check(partnerClasses);
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
                .that().resideInAPackage("..partner.application..")
                .should(dependOnPersistenceTechnology())
                .because("@Service·@Transactional 은 실용적 타협으로 허용하되 저장소 기술은 어댑터에 가둔다");
        rule.check(partnerClasses);
    }

    @Test
    void partner_는_다른_서비스_패키지에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.partner..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.point..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.loan..",
                        "github.lms.lemuel.operation..",
                        "github.lms.lemuel.marketing..")
                .because("파트너 콘솔은 Kafka 이벤트로만 사실을 받는다 — 코드로 물면 따로 배포할 수 없다");
        rule.check(partnerClasses);
    }

    @Test
    void 웹_어댑터에_쓰기_매핑이_없다() {
        ArchRule rule = noMethods()
                .that().areDeclaredInClassesThat().resideInAPackage("..partner.adapter.in.web..")
                .should().beAnnotatedWith(PostMapping.class)
                .orShould().beAnnotatedWith(PutMapping.class)
                .orShould().beAnnotatedWith(PatchMapping.class)
                .orShould().beAnnotatedWith(DeleteMapping.class)
                .because("이 서비스는 어떤 사실의 원본도 소유하지 않는다 — 여기서 쓰면 원본이 둘이 되고 "
                        + "다음 이벤트가 조용히 덮는다. 수정이 필요하면 원본 서비스에 API 를 내라");
        rule.check(partnerClasses);
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
                .filter(PartnerArchitectureTest::isPersistenceTech)
                .forEach(hits::add);
        for (JavaCodeUnit codeUnit : clazz.getCodeUnits()) {
            for (TryCatchBlock block : codeUnit.getTryCatchBlocks()) {
                block.getCaughtThrowables().stream()
                        .map(JavaClass::getName)
                        .filter(PartnerArchitectureTest::isPersistenceTech)
                        .forEach(hits::add);
            }
        }
        return hits;
    }
}
