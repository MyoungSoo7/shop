package github.lms.lemuel.operation;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
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
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * operation-service 의 헥사고날 아키텍처 + MSA 코드 경계를 강제하는 가드 (loan-service 패턴).
 *
 * <p>핵심 불변식:
 * <ul>
 *   <li>도메인은 application/adapter/config 에 의존하지 않는다 (의존 방향).</li>
 *   <li>application 은 adapter 에 의존하지 않는다 (config 의 OpsProperties 주입은 허용 —
 *       config 는 조립 계층이지 adapter 가 아니다).</li>
 *   <li>인바운드 어댑터는 {@code port.in} 만 경유한다 — 구체 서비스를 직접 물지 않는다.</li>
 *   <li>유스케이스는 저장소 기술을 모른다 — {@code org.springframework.dao} 등 금지.</li>
 *   <li>기능(feature)은 다른 기능의 내부(domain/adapter)를 직접 참조하지 않는다.</li>
 *   <li>★ operation-service 는 order/settlement/loan 패키지에 코드 의존 0
 *       — 신호는 Alertmanager webhook(Phase 1)·Kafka 이벤트(Phase 2)로만 수신한다.</li>
 * </ul>
 *
 * <h2>허용 목록(래칫)에 대하여</h2>
 * <p>새로 추가한 세 규칙은 도입 시점에 이미 위반이 있다. 위반을 이유로 규칙을 무르지 않고,
 * <b>현재 위반 집합을 이름으로 고정</b>한 뒤 리팩터가 진행되면서 줄여 나간다. 허용 목록은
 * 늘어날 수 없고({@code noClasses} 규칙이 막는다), 줄어들면
 * {@code 허용목록에_유효하지_않은_항목이_없다} 가 <b>실패</b>해 지우도록 강제한다.
 * 즉 이 목록은 방치되면 반드시 빨간불이 된다.
 */
class OperationArchitectureTest {

    private static final String OPERATION_ROOT = "github.lms.lemuel.operation.";

    /**
     * 임포트 건수 하한. operation-service 는 현재 약 310개를 임포트한다.
     * 모듈 분리 등으로 이 값을 밑돌면 규칙이 아니라 <b>임포트 자체</b>를 먼저 점검해야 한다.
     */
    private static final int MIN_IMPORTED_CLASSES = 200;

    /**
     * G1 — 인바운드 어댑터가 구체 서비스를 직접 참조한다. education·site 는 {@code port.in} 이 0개다.
     * 서비스의 <b>중첩 타입</b>({@code CourseAdminService.CourseNotFoundException},
     * {@code EnrollmentAdminService.CapacitySummary})까지 물고 있어, 포트만 뽑아서는 안 되고
     * 그 타입들을 각각 {@code domain.exception} 과 {@code port.in.dto} 로 들어내야 한다.
     */
    private static final Set<String> INBOUND_ADAPTER_ALLOWLIST = Set.of(
            OPERATION_ROOT + "education.adapter.in.web.AdminEducationController",
            OPERATION_ROOT + "education.adapter.in.web.AdminEnrollmentController",
            OPERATION_ROOT + "education.adapter.in.web.AdminLecturerController",
            OPERATION_ROOT + "education.adapter.in.web.EducationExceptionHandler",
            OPERATION_ROOT + "site.adapter.in.web.AdminPopupController",
            OPERATION_ROOT + "site.adapter.in.web.SiteExceptionHandler");

    /**
     * G2 — 유스케이스가 스프링 저장소 예외를 직접 분기한다.
     * {@code @Service}·{@code @Transactional} 은 실용적 타협으로 허용하지만
     * {@code org.springframework.dao} 는 다르다 — "우리 저장소는 스프링 데이터다" 가 유스케이스에 박힌다.
     *
     * <p><b>2026-08-27 비었다.</b> {@code IngestAlertService}·{@code AnomalyDetectionService} 가
     * {@code catch (DataIntegrityViolationException | OptimisticLockingFailureException)} 하던 것을
     * 기능별 {@code WriteConflictDetector} 아웃바운드 포트로 옮겼다. 다시 채우지 마라.
     */
    private static final Set<String> PERSISTENCE_TECH_ALLOWLIST = Set.<String>of();

    /**
     * G3 — 기능 간 결합. 두 종류가 섞여 있고 처방이 서로 다르다.
     *
     * <p><b>(a) anomaly → incident (쓰기)</b> — anomaly 가 incident 의 도메인 객체를 직접 생성한다.
     * {@code RaiseIncidentPort} 를 도입해 anomaly 자기 타입으로만 말하게 바꾼다.
     *
     * <p><b>(b) anomaly → signal (읽기)</b> — 이쪽이 더 깊다. {@code MetricSeriesQueryAdapter} 가
     * signal 의 <b>JPA 엔티티와 스프링 데이터 리포지터리를 직접</b> 읽고, 게다가 anomaly 자신의
     * 아웃바운드 포트 {@code LoadMetricSeriesPort} 시그니처가 {@code signal.domain.MetricBucket} 을
     * 그대로 노출한다. 포트가 남의 도메인 타입으로 말하면 포트를 둔 의미가 없다.
     * 처방은 둘 중 하나다 — signal 의 아웃바운드 포트를 경유하거나,
     * {@code MetricBucket} 을 공용 계측 개념으로 승격해 소유를 명확히 하거나.
     *
     * <p><b>2026-08-27 비었다.</b> (a) 는 {@code AnomalyIncidentApplier} 를 통째로 incident 로 되돌리고
     * (다루던 게 전부 incident 의 것이었다) 인바운드 포트 {@code RaiseAnomalyIncidentUseCase} 만 남겼다.
     * (b) 는 signal 이 인바운드 포트 {@code QueryMetricSeriesUseCase} 를 공개하고 anomaly 는
     * 자기 읽기 모델 {@code MetricPoint} 로 번역해 받는다. 다시 채우지 마라.
     *
     * <p>남은 방향 의존은 <b>기능의 {@code application.port.in} 뿐</b>이다 — 그건 그 기능이
     * 공개하기로 한 창구이므로 이 규칙이 막지 않는다. 막는 것은 {@code domain}·{@code adapter} 다.
     */
    private static final Set<String> CROSS_FEATURE_ALLOWLIST = Set.<String>of();

    /**
     * G4 — {@code operation.config} 는 합성 루트(composition root)다. 도메인 전략 빈
     * ({@code AnomalyEvaluator}·{@code BaselineStrategy}·{@code RollingWindowBaseline})을
     * 조립하려면 도메인을 알아야 한다. 우연히 통과하는 게 아니라 <b>의도된 예외</b>임을 여기 못 박는다.
     */
    private static final String COMPOSITION_ROOT_FEATURE = "config";

    private static final Set<String> PERSISTENCE_TECH_PACKAGES = Set.of(
            "org.springframework.dao", "org.springframework.data",
            "jakarta.persistence", "javax.persistence", "org.hibernate");

    private static JavaClasses operationClasses;

    @BeforeAll
    static void importClasses() {
        operationClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption((Location location) ->
                        !location.contains("/generated/") && !location.contains("/build/generated"))
                .importPackages("github.lms.lemuel.operation");
    }

    /**
     * 아래 모든 규칙은 {@link #operationClasses} 를 대상으로 한다. 임포트가 0개면 규칙 전부가
     * <b>공허 통과</b>(검사 대상 없이 green)한다 — 같은 리포의 order-service 가 ArchUnit 1.3.0 +
     * Java 25(class major 69) 조합에서 0개를 임포트한 채 4개 규칙 전부 green 이었다.
     * green 과 blind 는 겉으로 구분되지 않으므로 임포트 건수를 먼저 검사한다.
     */
    @Test
    void 검사대상이_공허하지_않다() {
        assertTrue(operationClasses.size() >= MIN_IMPORTED_CLASSES,
                "아키텍처 규칙의 검사 대상이 " + operationClasses.size() + "개다 (기대 최소 "
                        + MIN_IMPORTED_CLASSES + "개). ArchUnit 이 현재 바이트코드 버전을 읽지 못하면 "
                        + "0개를 임포트하고 이 클래스의 모든 규칙이 공허 통과한다.");
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

    /**
     * 인바운드 어댑터는 {@code port.in} 인터페이스만 경유한다.
     *
     * <p>{@code InboundPortReachabilityTest} 는 "모든 인바운드 포트는 어댑터에서 도달 가능하다" 를 본다.
     * 그건 포트가 0개인 기능(education·site)에서 검사 대상이 없어 <b>통과</b>한다. 이 규칙이 그 역(逆)이다.
     */
    @Test
    void 인바운드_어댑터는_application_service_에_직접_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..operation..adapter.in..")
                .and(notAllowlisted(INBOUND_ADAPTER_ALLOWLIST))
                .should().dependOnClassesThat()
                .resideInAPackage("..operation..application.service..")
                .because("인바운드 어댑터는 port.in 만 경유한다 (구체 서비스·그 중첩 타입 금지)")
                .allowEmptyShould(true);
        rule.check(operationClasses);
    }

    /**
     * 유스케이스는 저장소 기술을 모른다.
     *
     * <p><b>{@code dependOnClassesThat()} 로 쓰면 안 된다.</b> 실제 위반 2건은 전부
     * {@code catch (DataIntegrityViolationException | OptimisticLockingFailureException e)} 형태인데,
     * ArchUnit 의 {@code getDirectDependenciesFromSelf()} 는 <b>catch 절의 예외 타입을 포함하지 않는다</b>.
     * DSL 로 쓴 첫 판은 그래서 green 이었다 — 코드가 깨끗해서가 아니라 규칙이 못 봐서다.
     * 이 파일 맨 위 {@code 검사대상이_공허하지_않다} 와 같은 종류의 실패이고, 한 겹 더 안쪽이라 더 조용하다.
     * {@code getTryCatchBlocks()} (ArchUnit 1.0+) 로 catch 절까지 본다.
     */
    @Test
    void application_은_저장소_기술에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..operation..application..")
                .and(notAllowlisted(PERSISTENCE_TECH_ALLOWLIST))
                .should(dependOnPersistenceTechnology())
                .because("@Service·@Transactional 은 허용하되 저장소 기술은 어댑터에 가둔다")
                .allowEmptyShould(true);
        rule.check(operationClasses);
    }

    @Test
    void 기능은_타_기능의_domain_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that(new DescribedPredicate<JavaClass>("합성 루트가 아닌 operation 기능") {
                    @Override
                    public boolean test(JavaClass clazz) {
                        String feature = featureOf(clazz.getName());
                        return feature != null
                                && !COMPOSITION_ROOT_FEATURE.equals(feature)
                                && !CROSS_FEATURE_ALLOWLIST.contains(clazz.getName());
                    }
                })
                .should(dependOnAnotherFeaturesInternals())
                .because("기능 간 통신은 포트(또는 이벤트)를 경유한다 — 타 기능의 domain/adapter 직접 참조 금지")
                .allowEmptyShould(true);
        rule.check(operationClasses);
    }

    /**
     * 허용 목록이 <b>썩지 않게</b> 만드는 규칙. 리팩터로 위반이 사라졌는데 목록에 이름이 남아 있으면
     * 그 항목은 이후 실제 위반을 조용히 가려 준다. 그래서 "더 이상 위반하지 않는 항목" 을 실패로 잡는다.
     */
    @Test
    void 허용목록에_유효하지_않은_항목이_없다() {
        assertEquals(Set.of(), stale(INBOUND_ADAPTER_ALLOWLIST, OperationArchitectureTest::usesConcreteService),
                "인바운드 어댑터 허용 목록에서 아래 항목을 지워라 — 이미 고쳐졌다");
        assertEquals(Set.of(), stale(PERSISTENCE_TECH_ALLOWLIST, OperationArchitectureTest::usesPersistenceTech),
                "저장소 기술 허용 목록에서 아래 항목을 지워라 — 이미 고쳐졌다");
        assertEquals(Set.of(), stale(CROSS_FEATURE_ALLOWLIST, OperationArchitectureTest::usesAnotherFeature),
                "기능 간 결합 허용 목록에서 아래 항목을 지워라 — 이미 고쳐졌다");
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
     *
     * <p><b>한계:</b> 슬라이스가 모듈 단위라 operation <i>안쪽</i>의 기능 간 순환은 원리적으로 보이지 않는다.
     * 그 구멍은 {@code 기능은_타_기능의_domain_adapter_에_의존하지_않는다} 가 대신 막는다.
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

    // ---------------------------------------------------------------- helpers

    /** {@code github.lms.lemuel.operation.<feature>....} 에서 feature 를 뽑는다. 밖이면 null. */
    private static String featureOf(String className) {
        if (!className.startsWith(OPERATION_ROOT)) {
            return null;
        }
        String rest = className.substring(OPERATION_ROOT.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? null : rest.substring(0, dot);
    }

    /** operation 기준 계층 이름({@code domain}/{@code application}/{@code adapter}). 없으면 null. */
    private static String layerOf(String className) {
        String feature = featureOf(className);
        if (feature == null) {
            return null;
        }
        String rest = className.substring(OPERATION_ROOT.length() + feature.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? null : rest.substring(0, dot);
    }

    /**
     * 중첩 클래스는 {@code Outer$Inner} 라는 <b>별개의</b> JavaClass 로 임포트된다.
     * 허용 목록에 바깥 클래스만 적어 두면 안쪽이 규칙을 그대로 위반해 새어 나가므로
     * (실제로 {@code AdminEnrollmentController$SummaryResponse} 가 그렇게 걸렸다) 최상위 이름으로 맞춘다.
     */
    private static String topLevelNameOf(String className) {
        int dollar = className.indexOf('$');
        return dollar < 0 ? className : className.substring(0, dollar);
    }

    private static DescribedPredicate<JavaClass> notAllowlisted(Set<String> allowlist) {
        return new DescribedPredicate<>("허용 목록에 없는") {
            @Override
            public boolean test(JavaClass clazz) {
                return !allowlist.contains(topLevelNameOf(clazz.getName()));
            }
        };
    }

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
                .filter(OperationArchitectureTest::isPersistenceTech)
                .forEach(hits::add);
        // ⚠️ catch 절의 예외 타입은 위 의존 목록에 들어오지 않는다. 별도로 훑는다.
        for (JavaCodeUnit codeUnit : clazz.getCodeUnits()) {
            for (TryCatchBlock block : codeUnit.getTryCatchBlocks()) {
                block.getCaughtThrowables().stream()
                        .map(JavaClass::getName)
                        .filter(OperationArchitectureTest::isPersistenceTech)
                        .forEach(hits::add);
            }
        }
        return hits;
    }

    private static ArchCondition<JavaClass> dependOnAnotherFeaturesInternals() {
        return new ArchCondition<>("다른 기능의 domain·adapter 에 의존") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (String target : anotherFeatureTargets(clazz)) {
                    events.add(SimpleConditionEvent.satisfied(clazz,
                            clazz.getName() + " → " + target));
                }
            }
        };
    }

    /** {@code clazz} 가 참조하는 <b>다른 기능</b>의 domain·adapter 타입 이름들. */
    private static Set<String> anotherFeatureTargets(JavaClass clazz) {
        String ownFeature = featureOf(clazz.getName());
        if (ownFeature == null) {
            return Set.of();
        }
        Set<String> hits = new TreeSet<>();
        for (Dependency dependency : clazz.getDirectDependenciesFromSelf()) {
            String target = dependency.getTargetClass().getName();
            String targetFeature = featureOf(target);
            if (targetFeature == null || targetFeature.equals(ownFeature)) {
                continue;
            }
            String targetLayer = layerOf(target);
            if ("domain".equals(targetLayer) || "adapter".equals(targetLayer)) {
                hits.add(target);
            }
        }
        return hits;
    }

    private static boolean usesAnotherFeature(JavaClass clazz) {
        return !anotherFeatureTargets(clazz).isEmpty();
    }

    private static boolean usesConcreteService(JavaClass clazz) {
        return clazz.getDirectDependenciesFromSelf().stream()
                .map(d -> d.getTargetClass().getName())
                .anyMatch(name -> name.startsWith(OPERATION_ROOT)
                        && name.contains(".application.service."));
    }

    private static boolean usesPersistenceTech(JavaClass clazz) {
        return !persistenceTechTargets(clazz).isEmpty();
    }

    /**
     * 허용 목록 중 <b>더 이상 위반하지 않는</b>(= 지워야 할) 항목들.
     *
     * <p>중첩 클래스가 대신 위반하고 있을 수 있으므로 최상위 이름이 같은 클래스를 <b>전부</b> 본다.
     */
    private static Set<String> stale(Set<String> allowlist, java.util.function.Predicate<JavaClass> stillViolates) {
        Map<String, List<JavaClass>> byTopLevel = operationClasses.stream()
                .collect(Collectors.groupingBy(clazz -> topLevelNameOf(clazz.getName())));
        Set<String> result = new LinkedHashSet<>();
        for (String name : new TreeSet<>(allowlist)) {
            List<JavaClass> family = byTopLevel.get(name);
            if (family == null) {
                result.add(name + " (클래스가 사라졌다)");
            } else if (family.stream().noneMatch(stillViolates)) {
                result.add(name);
            }
        }
        return result;
    }
}
