package github.lms.lemuel.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * 기능 슬라이스 매처. order-service 는 기능이 루트 바로 아래에 있어
     * {@link #슬라이스_사이에_순환_의존이_없다()} 의 매처와 같은 문자열이다 — operation-service 처럼
     * 모듈 한 겹이 더 있는 구조가 아니라서 "모듈 슬라이스"와 "기능 슬라이스"가 여기서는 같은 것을 가리킨다.
     * 그래서 순환 규칙을 둘로 나누지 않고, 대신 매처가 아무것도 못 잡는 경우만 아래에서 못 박는다.
     */
    private static final String FEATURE_SLICE = "github.lms.lemuel.(*)..";

    /** 기능 개수 하한. 현재 24개(addressbook…wishlist). 매처가 죽으면 순환 규칙이 공허 통과한다. */
    private static final int MIN_FEATURES = 15;

    /**
     * 합성 루트. 빈 조립이 일이라 모든 기능을 알아야 하고, 그건 부채가 아니라 성질이다.
     *
     * <p>{@code Set} 이 아니라 {@code String} 인 이유는 operation-service 와 같다 — 허용 목록은 늘었다
     * 줄었다 하지만 합성 루트는 하나뿐이고 영구적이다. 목록으로 두면 둘째 셋째가 슬쩍 들어온다.
     *
     * <p>{@code web}(= {@code web.security.ResourceOwnership}) 은 여기 넣지 않는다. 공용 커널이긴 하지만
     * 계층 이름이 {@code domain}·{@code adapter} 가 아니라서 아래 캡슐화 규칙이 애초에 잡지 않는다.
     */
    private static final String COMPOSITION_ROOT_FEATURE = "config";

    /**
     * 인바운드 어댑터가 구체 서비스를 직접 무는 것을 <b>한시적으로</b> 허용하는 목록.
     *
     * <p>operation-service 는 2026-08-27 에 같은 목록을 0개까지 비웠다. 여기 이름이 있다는 것은
     * "아직 그 기능에 {@code port.in} 을 안 세웠다"는 뜻이지, 안 세워도 된다는 뜻이 아니다.
     * <b>새 이름을 추가하지 마라</b> — 늘어나는 순간 래칫이 아니라 그냥 주석이 된다.
     */
    private static final Set<String> INBOUND_ADAPTER_ALLOWLIST = Set.of(
            // category — 네 컨트롤러가 서비스 둘(EcommerceCategoryService·DisplaySectionService)을 공유한다.
            "github.lms.lemuel.category.adapter.in.web.AdminDisplaySectionController",
            "github.lms.lemuel.category.adapter.in.web.AdminEcommerceCategoryController",
            "github.lms.lemuel.category.adapter.in.web.PublicDisplaySectionController",
            "github.lms.lemuel.category.adapter.in.web.PublicEcommerceCategoryController",
            // 나머지는 포트를 이미 일부 쓰면서 서비스 하나만 남긴 형태다 — 그 하나가 남은 이유가 대개
            // 서비스의 중첩 타입이다. 고칠 때 포트부터 세우지 말고 중첩 타입부터 꺼내라.
            "github.lms.lemuel.giftcard.adapter.in.web.GiftCardController",
            "github.lms.lemuel.payment.adapter.in.api.SplitPaymentController",
            "github.lms.lemuel.point.adapter.in.web.PointController",
            "github.lms.lemuel.product.adapter.in.web.ProductController",
            "github.lms.lemuel.product.adapter.in.web.ProductImageController");

    /**
     * 기능이 다른 기능의 {@code domain}·{@code adapter} 를 직접 참조하는 것을 한시적으로 허용하는 목록.
     *
     * <p>여기 있는 것들은 대부분 "옆 기능의 엔티티를 그냥 읽어 쓰는" 어댑터·서비스다. 고치는 방향은
     * 그 기능의 {@code port.in} 을 세워 창구로 삼는 것이지, 참조를 숨기는 게 아니다.
     */
    private static final Set<String> CROSS_FEATURE_ALLOWLIST = Set.of(
            // 아웃바운드 어댑터가 옆 기능의 엔티티를 직접 읽는 형태. 원래 어댑터의 자리는
            // "밖(DB·외부 API)" 인데 여기서는 "옆 기능"이 밖 노릇을 하고 있다.
            "github.lms.lemuel.bulkorder.adapter.out.order.BulkOrderLineAdapter",
            "github.lms.lemuel.menu.adapter.out.rbac.RolePermissionCodesAdapter",
            "github.lms.lemuel.order.adapter.out.shipping.ShipmentCreationAdapter",
            "github.lms.lemuel.order.adapter.out.user.UserExistenceAdapter",
            "github.lms.lemuel.payment.adapter.out.persistence.OrderAdapter",
            "github.lms.lemuel.point.adapter.out.user.TransferRecipientAdapter",
            "github.lms.lemuel.wishlist.adapter.out.product.WishlistProductQueryAdapter",
            // 서비스·포트가 옆 기능의 도메인 타입을 시그니처에 그대로 쓰는 형태. 이쪽이 더 무겁다 —
            // CheckoutCartUseCase 는 인바운드 <b>계약</b>에 order 도메인이 박혀 있어서, order 를 고치면
            // cart 의 공개 창구가 따라 바뀐다.
            "github.lms.lemuel.cart.adapter.in.web.CartController",
            "github.lms.lemuel.cart.application.port.in.CheckoutCartUseCase",
            "github.lms.lemuel.cart.application.service.CheckoutCartService",
            "github.lms.lemuel.order.application.service.CancelOrderItemsService",
            "github.lms.lemuel.order.application.service.CreateMultiItemOrderService",
            "github.lms.lemuel.order.application.service.CreateOrderService",
            "github.lms.lemuel.order.application.service.PreviewCouponService",
            "github.lms.lemuel.review.adapter.in.web.AdminReviewController");

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

    /** {@code github.lms.lemuel.<기능>....} 에서 기능 세그먼트를 뽑는다. 밖이면 {@code null}. */
    private static String featureOf(String className) {
        if (!className.startsWith(ROOT_PACKAGE)) {
            return null;
        }
        String rest = className.substring(ROOT_PACKAGE.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? null : rest.substring(0, dot);
    }

    /** 기능 기준 계층 이름({@code domain}/{@code application}/{@code adapter}). 없으면 {@code null}. */
    private static String layerOf(String className) {
        String feature = featureOf(className);
        if (feature == null) {
            return null;
        }
        String rest = className.substring(ROOT_PACKAGE.length() + feature.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? null : rest.substring(0, dot);
    }

    /**
     * 중첩 클래스는 {@code Outer$Inner} 라는 별개의 {@link JavaClass} 로 임포트된다.
     * 허용 목록에 바깥 이름만 적어 두면 안쪽이 규칙을 그대로 위반해 새어 나가므로 최상위 이름으로 맞춘다.
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

    private static ArchCondition<JavaClass> dependOnAnotherFeaturesInternals() {
        return new ArchCondition<>("다른 기능의 domain·adapter 에 의존") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (String target : anotherFeatureTargets(clazz)) {
                    events.add(SimpleConditionEvent.satisfied(clazz, clazz.getName() + " → " + target));
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
                .map(dependency -> dependency.getTargetClass().getName())
                .anyMatch(name -> name.startsWith(ROOT_PACKAGE) && name.contains(".application.service."));
    }

    /**
     * 허용 목록 중 <b>더 이상 위반하지 않는</b>(= 지워야 할) 항목들.
     *
     * <p>중첩 클래스가 대신 위반하고 있을 수 있으므로 최상위 이름이 같은 클래스를 전부 본다.
     */
    private static Set<String> stale(Set<String> allowlist, java.util.function.Predicate<JavaClass> stillViolates) {
        Map<String, List<JavaClass>> byTopLevel = mainClasses.stream()
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
     * 인바운드 어댑터는 {@code port.in} 인터페이스만 경유한다.
     *
     * <p>operation-service 에서 배운 것: 이 규칙의 걸림돌은 대개 포트가 아니라 <b>서비스 안의 중첩 타입</b>이다.
     * 컨트롤러가 {@code XService.XCommand} 를 받거나 예외 핸들러가 {@code XService.NotFoundException} 을
     * 잡고 있으면 포트를 아무리 세워도 구체 서비스 임포트가 남는다. 중첩 타입을 먼저 밖으로 꺼내야 한다.
     *
     * <p>중첩 클래스는 {@code Outer$Inner} 로 <b>따로</b> 임포트되므로 허용 목록은 최상위 이름으로 맞춘다
     * ({@link #topLevelNameOf(String)}).
     */
    @Test
    void 인바운드_어댑터는_application_service_에_직접_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..adapter.in..")
                .and(notAllowlisted(INBOUND_ADAPTER_ALLOWLIST))
                .should().dependOnClassesThat()
                .resideInAPackage("..application.service..")
                .because("인바운드 어댑터는 port.in 만 경유한다 (구체 서비스·그 중첩 타입 금지)")
                .allowEmptyShould(true);

        rule.check(mainClasses);
    }

    /**
     * 기능은 다른 기능의 {@code domain}·{@code adapter} 를 직접 참조하지 않는다.
     *
     * <p>기존 {@link #adaptersShouldNotDirectlyReferenceOtherDomainsPersistence()} 는 <b>어댑터가
     * 남의 persistence 를</b> 무는 경우만 본다. 실제로 새는 곳은 그보다 넓다 — 서비스가 남의
     * {@code domain} 엔티티를 그대로 받아 쓰는 형태가 다수다. 그쪽은 저 규칙이 원리적으로 못 본다.
     *
     * <p>합성 루트는 제외한다. 조립이 일이라 모든 기능을 알아야 하기 때문이고, 이건 순환 규칙에서
     * config 를 빼는 것과 같은 이유다.
     */
    @Test
    void 기능은_타_기능의_domain_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that(new DescribedPredicate<JavaClass>("합성 루트가 아니고 허용 목록에도 없는 기능") {
                    @Override
                    public boolean test(JavaClass clazz) {
                        String feature = featureOf(clazz.getName());
                        return feature != null
                                && !COMPOSITION_ROOT_FEATURE.equals(feature)
                                && !CROSS_FEATURE_ALLOWLIST.contains(topLevelNameOf(clazz.getName()));
                    }
                })
                .should(dependOnAnotherFeaturesInternals())
                .because("기능 간 통신은 포트(또는 이벤트)를 경유한다 — 타 기능의 domain/adapter 직접 참조 금지")
                .allowEmptyShould(true);

        rule.check(mainClasses);
    }

    /**
     * 허용 목록이 <b>썩지 않게</b> 만드는 규칙. 리팩터로 위반이 사라졌는데 목록에 이름이 남아 있으면
     * 그 항목은 이후 <i>새로 생긴</i> 위반을 조용히 가려 준다 — 래칫이 거꾸로 도는 것이다.
     * 그래서 "더 이상 위반하지 않는 항목"을 실패로 잡는다.
     */
    @Test
    void 허용목록에_유효하지_않은_항목이_없다() {
        assertEquals(Set.of(), stale(INBOUND_ADAPTER_ALLOWLIST, HexagonalArchitectureTest::usesConcreteService),
                "인바운드 어댑터 허용 목록에서 아래 항목을 지워라 — 이미 고쳐졌다");
        assertEquals(Set.of(), stale(CROSS_FEATURE_ALLOWLIST, HexagonalArchitectureTest::usesAnotherFeature),
                "기능 간 결합 허용 목록에서 아래 항목을 지워라 — 이미 고쳐졌다");
    }

    /**
     * 슬라이스 매처가 실제로 기능을 잡고 있는지 못 박는다.
     *
     * <p>{@link #importedClassesMustNotBeVacuous()} 는 임포트 <i>건수</i>만 본다. 클래스는 잔뜩 읽혔는데
     * 패키지 구조가 바뀌어 매처가 0개를 잡는 경우는 그 검사를 통과하고, 그러면 순환 규칙은 빈 그래프를
     * 보고 조용히 통과한다. 공허 통과에는 층이 있다 — 임포트 0개, 슬라이스 0개, 조건이 못 보는 의존
     * (catch 절 등). 셋 다 겉으로는 똑같은 green 이다.
     */
    @Test
    void 기능_슬라이스가_공허하지_않다() {
        long count = Slices.matching(FEATURE_SLICE).transform(mainClasses).stream().count();
        assertTrue(count >= MIN_FEATURES,
                "기능 슬라이스가 " + count + "개다 (기대 최소 " + MIN_FEATURES
                        + "개). 매처가 아무것도 잡지 못하면 순환 규칙은 공허 통과한다.");
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
