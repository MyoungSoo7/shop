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
import com.tngtech.archunit.library.dependencies.Slices;
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
 *
 * <p><b>2026-08-27 — 세 목록(G1·G2·G3)이 모두 비었다.</b> 래칫이 제 일을 끝냈다는 뜻이다.
 * 지금부터 이 규칙들은 예외 없는 규칙이다. 새 위반이 생기면 목록에 이름을 더하지 말고
 * 코드를 고쳐라 — 목록이 다시 채워지는 순간 "예외가 있는 규칙"으로 되돌아가고, 그때부터는
 * 누가 봐도 그 예외가 원래 있던 것인지 새로 판 구멍인지 구분할 수 없다.
 *
 * <h2>유일하게 남은 예외 — 합성 루트</h2>
 * <p>허용 목록이 아닌 <b>구조적 예외</b>가 하나 있다: {@code operation.config}
 * ({@link #COMPOSITION_ROOT_FEATURE}). 이건 리팩터로 없앨 부채가 아니라 없앨 수 없는 성질이라
 * 목록이 아니라 상수로 박아 두고, 두 규칙에서 각각 다르게 취급한다.
 *
 * <ul>
 *   <li><b>기능 순환 그래프에서 제외</b>({@code 기능_사이에_순환_의존이_없다}) — 합성 루트는
 *       모든 기능의 빈을 조립하므로 전부를 알고({@code config} → 각 기능), 각 기능은
 *       {@code OpsProperties} 를 읽으므로 config 를 안다(각 기능 → {@code config}).
 *       구조상 <b>항상 양방향</b>이라 순환으로 셀 대상이 아니다. 여기서 빼지 않으면 이 규칙은
 *       코드가 무엇을 하든 영원히 빨간불이고, 곧 {@code @Disabled} 가 붙는다.</li>
 *   <li><b>기능 간 참조 규칙에서는 제외하지 않는다</b>({@code 기능은_다른_기능의_내부를_직접_참조하지_않는다}
 *       의 대상) — 합성 루트가 각 기능의 구체 타입을 아는 것은 정상이지만, 그건 "config 가
 *       기능을 안다" 한 방향뿐이다. 어떤 기능이 <b>다른 기능의 내부</b>를 물면 config 와 무관하게
 *       위반이다. 두 규칙이 config 를 서로 다르게 다루는 게 실수처럼 보이지만, 각각이 재는 것이
 *       다르기 때문이다 — 앞은 <i>순환</i>, 뒤는 <i>캡슐화</i>.</li>
 * </ul>
 *
 * <p>{@code application 은 adapter 에 의존하지 않는다} 에서 {@code config} 를 금지 패키지로 넣지
 * 않은 것도 같은 이유다. config 는 조립 계층이지 어댑터가 아니다 — 유스케이스가
 * {@code OpsProperties} 를 주입받는 것은 기술에 묶이는 게 아니라 설정값을 받는 것이다.
 */
class OperationArchitectureTest {

    private static final String OPERATION_ROOT = "github.lms.lemuel.operation.";

    /**
     * 임포트 건수 하한. operation-service 는 현재 약 310개를 임포트한다.
     * 모듈 분리 등으로 이 값을 밑돌면 규칙이 아니라 <b>임포트 자체</b>를 먼저 점검해야 한다.
     */
    private static final int MIN_IMPORTED_CLASSES = 200;

    /** operation 기능 하나 = 슬라이스 하나. */
    private static final String OPERATION_FEATURE_SLICE = "github.lms.lemuel.operation.(*)..";

    /**
     * 기능 슬라이스 개수 하한. 현재 10개(anomaly·audit·board·config·dashboard·education·
     * incident·notification·signal·site)다. 매처가 헛돌면 0개가 되고 순환 규칙이 공허 통과한다.
     */
    private static final int MIN_FEATURES = 8;

    /**
     * G1 — 인바운드 어댑터가 구체 서비스를 직접 참조한다.
     *
     * <p><b>2026-08-27 비었다.</b> site·education 두 기능에 {@code port.in} 을 세우면서 마지막 다섯
     * 항목이 빠졌다. 걸림돌은 포트가 아니라 서비스의 <b>중첩 타입</b>이었다 —
     * {@code @RestControllerAdvice} 가 {@code CourseAdminService.CourseNotFoundException} 을 잡는 한
     * 포트를 아무리 뽑아도 어댑터는 구체 서비스를 임포트한다. 그래서 예외 넷은
     * {@code education.domain.exception} 으로, {@code CapacitySummary} 는 {@code port.in} 으로
     * 먼저 들어낸 다음에야 이 목록이 비었다.
     *
     * <p>다시 채우지 마라. 새 컨트롤러가 서비스를 직접 물면 그건 아직 포트를 안 세운 것이다.
     */
    private static final Set<String> INBOUND_ADAPTER_ALLOWLIST = Set.<String>of();

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
     *
     * <p>G1~G3 와 달리 이건 {@code Set} 이 아니라 {@code String} 상수 하나다 — 의도적이다.
     * 허용 목록은 "지금은 어기지만 언젠가 고칠 것들"이라 늘었다 줄었다 하지만, 합성 루트는
     * <b>하나뿐이고 영구적</b>이다. 목록으로 두면 둘째 셋째가 슬쩍 들어온다.
     *
     * <p>쓰이는 곳은 {@link #compositionRoot(JavaClass)} 한 군데, 그리고 그걸 거쳐
     * {@code 기능_사이에_순환_의존이_없다} 뿐이다. <b>캡슐화 규칙에는 쓰이지 않는다</b> —
     * 클래스 javadoc 의 "유일하게 남은 예외" 절이 그 이유를 적어 뒀다.
     *
     * <p>합성 루트가 늘어야 할 것 같으면 그건 예외를 넓힐 때가 아니라, 그 새 클래스가 정말
     * 조립만 하는지 다시 볼 때다. 조립 외에 판단을 하고 있다면 그건 유스케이스지 config 가 아니다.
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
     * 그건 포트가 0개인 기능에서 검사 대상이 없어 <b>통과</b>한다 — 2026-08-27 이전의 education·site 가
     * 정확히 그랬다. 이 규칙이 그 역(逆)이라, 둘을 같이 걸어야 구멍이 없다.
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
     * <p>이 규칙은 모듈 <i>사이</i>만 본다. operation <b>안쪽</b>의 기능 간 순환은
     * {@code 기능_사이에_순환_의존이_없다} 가 맡는다.
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

    /**
     * 기능(feature) 사이에 순환 의존이 없다 — 위 모듈 단위 규칙이 원리적으로 못 보던 구멍.
     *
     * <p>{@code 기능은_타_기능의_domain_adapter_에_의존하지_않는다} 는 <b>어디를</b> 참조하는지만 본다.
     * 그래서 A 의 포트를 B 가 쓰고 B 의 포트를 A 가 쓰는 형태는 규칙상 전부 합법이지만,
     * 그건 두 기능을 하나로 묶어 버린다 — 따로 이해할 수도, 따로 옮길 수도 없다.
     * 방향이 아니라 <b>모양</b>을 보는 규칙이 따로 필요한 이유다.
     *
     * <p><b>config 는 그래프에서 뺀다.</b> 합성 루트는 모든 기능의 빈을 조립하므로 전부를 알아야 하고
     * ({@code config} → 각 기능), 각 기능은 {@code OpsProperties} 를 읽으므로 config 를 안다
     * (각 기능 → {@code config}). 이건 구조상 항상 양방향이라 순환으로 셀 대상이 아니다.
     * 합성 루트를 기능처럼 세면 규칙은 첫날부터 빨간불이고, 그 빨간불은 아무것도 알려주지 않는다.
     *
     * <p>슬라이스가 0개면 이 규칙도 공허 통과하므로 개수를 먼저 못 박는다.
     */
    @Test
    void 기능_사이에_순환_의존이_없다() {
        Slices features = Slices.matching(OPERATION_FEATURE_SLICE).transform(operationClasses);
        assertTrue(features.stream().count() >= MIN_FEATURES,
                "기능 슬라이스가 " + features.stream().count() + "개다 (기대 최소 " + MIN_FEATURES
                        + "개). 슬라이스 매처가 아무것도 잡지 못하면 순환 규칙은 공허 통과한다.");

        SlicesRuleDefinition.slices()
                .matching(OPERATION_FEATURE_SLICE)
                .should().beFreeOfCycles()
                .ignoreDependency(inCompositionRoot(), DescribedPredicate.alwaysTrue())
                .ignoreDependency(DescribedPredicate.alwaysTrue(), inCompositionRoot())
                .because("기능 간 의존은 한 방향이어야 한다 — 서로 물면 둘이 사실상 한 덩어리다")
                .check(operationClasses);
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

    /** 합성 루트({@code operation.config})에 속하는 클래스. 기능 순환 그래프에서 제외하는 데 쓴다. */
    private static DescribedPredicate<JavaClass> inCompositionRoot() {
        return new DescribedPredicate<>("합성 루트(" + COMPOSITION_ROOT_FEATURE + ")") {
            @Override
            public boolean test(JavaClass clazz) {
                return COMPOSITION_ROOT_FEATURE.equals(featureOf(clazz.getName()));
            }
        };
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
