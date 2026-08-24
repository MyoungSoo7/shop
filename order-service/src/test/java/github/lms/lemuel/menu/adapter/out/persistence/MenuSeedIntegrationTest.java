package github.lms.lemuel.menu.adapter.out.persistence;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.menu.domain.Menu;
import github.lms.lemuel.menu.domain.MenuArea;
import github.lms.lemuel.menu.domain.MenuType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시드된 메뉴 트리가 이관 전 하드코딩 네비게이션과 정확히 같은지 검증한다.
 *
 * <p>이 P-1 단계의 목표는 "기능 0 추가, 회귀 0" 이다. 마이그레이션 SQL 이 실제 Postgres 에
 * 적용된 결과를 세어 보지 않으면 시드가 반쯤 들어가도 아무도 모른다 — 그래서 DDL 검증
 * (SchemaIntegrationTest)과 별개로 데이터까지 본다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OutboxSchema.class, MenuPersistenceAdapter.class})
@ActiveProfiles("test")
class MenuSeedIntegrationTest {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("inter")
            .withUsername("lemuel")
            .withPassword("lemuel");

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired MenuPersistenceAdapter adapter;

    private Map<String, Menu> byName() {
        return adapter.findAll().stream().collect(Collectors.toMap(Menu::getName, Function.identity()));
    }

    private List<Menu> childrenOf(String parentName) {
        Menu parent = byName().get(parentName);
        return adapter.findAll().stream()
                .filter(m -> parent.getId().equals(m.getParentId()))
                .sorted(java.util.Comparator.comparingInt(Menu::getSortOrder))
                .toList();
    }

    @Test
    @DisplayName("시드 총 31행 — 커머스 + 운영 범위")
    void seedsExactlyThirtyOne() {
        assertThat(adapter.findAll()).hasSize(31);
    }

    @Test
    @DisplayName("최상위 11개가 상단 네비 순서대로 들어간다")
    void rootsInOrder() {
        List<Menu> roots = adapter.findAll().stream()
                .filter(m -> m.getParentId() == null)
                .sorted(java.util.Comparator.comparingInt(Menu::getSortOrder))
                .toList();

        assertThat(roots).extracting(Menu::getName).containsExactly(
                // 상품관리는 사라진 '정산' 그룹이 쓰던 자리(1)로 올라왔다 — 대시보드 바로 뒤다.
                "대시보드", "상품관리", "배송", "승인", "시스템 관리",
                // 대량주문은 관리자 기능이 아니라 구매자가 자기 주문을 올리는 경로다 — SHOP 최상위.
                // 나눠 결제는 주문(20)과 잔액 확인(30) 사이 — 주문에서 결제로 이어지는 순서다.
                // 내 알림(35)은 내 포인트·상품권(30) 다음 — 둘 다 "내 것"을 보는 개인 화면이다.
                "주문하기", "추천받기", "대량주문", "나눠 결제", "내 포인트·상품권", "내 알림");
    }

    @Test
    @DisplayName("상품관리는 최상위 ITEM 이다 — 그룹 없이 바로 들어간다")
    void productIsRootItem() {
        Menu product = byName().get("상품관리");

        assertThat(product.getParentId()).isNull();
        assertThat(product.getType()).isEqualTo(MenuType.ITEM);
        assertThat(product.getPath()).isEqualTo("/product");
        assertThat(product.allowedRoles()).containsExactlyInAnyOrder("ADMIN", "MANAGER");
    }

    @Test
    @DisplayName("배송 사이드바 2개 — 배송비 정책만 ADMIN 전용")
    void shippingChildren() {
        List<Menu> children = childrenOf("배송");

        assertThat(byName().get("배송").getType()).isEqualTo(MenuType.GROUP);
        assertThat(children).extracting(Menu::getName).containsExactly("배송 관리", "배송비 정책");
        assertThat(children).extracting(Menu::getPath)
                // 화면 URL 은 /admin/shipping/policies — 배송 그룹 아래다. API 는 /admin/shipping-policies/**
                // 로 그대로이며, 화면이 그 URL 을 쓰면 새로고침 때 API 응답이 렌더된다(V20260821230000).
                .containsExactly("/admin/shipping", "/admin/shipping/policies");
        // 서버가 /admin/shipping-policies/** 를 ADMIN 으로 막는다 — MANAGER 에게 보이면 죽은 링크다.
        assertThat(children.get(1).allowedRoles()).containsExactly("ADMIN");
        assertThat(children.get(0).allowedRoles()).containsExactlyInAnyOrder("ADMIN", "MANAGER");
    }

    @Test
    @DisplayName("시스템 사이드바 18개 — 앞 3개와 게시판 관리가 RBAC permission 과 짝지어진다")
    void systemChildren() {
        List<Menu> children = childrenOf("시스템 관리");

        // 분류(카테고리) → 편성(진열) → 선택지(옵션) → 관제(운영) 순. 뒤에 붙는 화면마다
        // 운영관리의 sort_order 를 한 칸씩 밀어 이 순서를 유지한다.
        // 환불 운영·셀러 등급은 맨 뒤다 — 사라진 '정산운영' 그룹에서 옮겨 오며 MAX(sort_order)+1 로
        // 이어 붙였다(V20260825200000). 둘 다 부르는 API 는 order-service 것이라 여기서도 살아 있다.
        assertThat(children).extracting(Menu::getName).containsExactly(
                "메뉴 관리", "공통코드 관리", "RBAC 관리", "이커머스 카테고리",
                "진열 편성", "옵션 카탈로그", "운영관리", "게시판 관리", "교육 관리",
                "포인트 운영", "기프트카드 운영", "감사 로그", "회원 관리", "조직 · 멤버십",
                "리뷰 관리", "쿠폰 운영", "환불 운영", "셀러 등급");
        assertThat(children).extracting(Menu::getRequiredPermission).containsExactly(
                "SYSTEM_MENU_MANAGE", "SYSTEM_CODE_MANAGE", "SYSTEM_RBAC_MANAGE",
                null, null, null, null, "SYSTEM_BOARD_MANAGE", null, null, null,
                null, null, null, null, null, null, null);
        // 환불은 ADMIN·MANAGER — 서버가 /admin/refunds/** 를 그 등급으로 막는다(조회 전용 표면).
        // 시스템 그룹 안에서 유일하게 등급이 낮은 항목이라 명시적으로 못 박는다.
        assertThat(children.stream().filter(m -> m.getName().equals("환불 운영"))
                .findFirst().orElseThrow().allowedRoles())
                .containsExactlyInAnyOrder("ADMIN", "MANAGER");
        // 셀러 등급은 ADMIN 전용 — 서버가 /admin/seller-tiers/** 를 그 등급으로 막는다.
        assertThat(children.stream().filter(m -> m.getName().equals("셀러 등급"))
                .findFirst().orElseThrow().allowedRoles())
                .containsExactly("ADMIN");
        // 옮겨 온 두 항목은 경로도 화면 URL 로 바뀌었다 — API 경로와 겹치면 새로고침이 API 응답을 렌더한다.
        assertThat(children.stream().filter(m -> m.getName().equals("환불 운영"))
                .findFirst().orElseThrow().getPath()).isEqualTo("/admin/system/refunds");
        assertThat(children.stream().filter(m -> m.getName().equals("셀러 등급"))
                .findFirst().orElseThrow().getPath()).isEqualTo("/admin/system/seller-tiers");
    }

    @Test
    @DisplayName("시스템 루트는 상단 네비에 '시스템', 사이드바에 '시스템 관리' 로 보인다")
    void systemRootLabels() {
        Menu system = byName().get("시스템 관리");

        assertThat(system.displayLabel()).isEqualTo("시스템");
        assertThat(system.getDescription()).isEqualTo("System Administration");
        assertThat(system.getType()).isEqualTo(MenuType.GROUP);
        assertThat(system.getArea()).isEqualTo(MenuArea.SYSTEM);
    }

    @Test
    @DisplayName("모든 행에 영역이 채워져 있고, 자식은 부모와 같은 영역이다")
    void areaIsConsistent() {
        List<Menu> all = adapter.findAll();
        Map<Long, Menu> byId = all.stream().collect(Collectors.toMap(Menu::getId, Function.identity()));

        assertThat(all).allSatisfy(m -> assertThat(m.getArea()).isNotNull());
        assertThat(all).allSatisfy(m -> {
            if (m.getParentId() != null) {
                assertThat(m.getArea()).isEqualTo(byId.get(m.getParentId()).getArea());
            }
        });
    }

    @Test
    @DisplayName("깊이는 2 단계를 넘지 않는다 (도메인 상한 3 이내)")
    void depthWithinLimit() {
        Map<Long, Menu> byId = adapter.findAll().stream()
                .collect(Collectors.toMap(Menu::getId, Function.identity()));

        for (Menu menu : byId.values()) {
            int depth = 1;
            Long cursor = menu.getParentId();
            while (cursor != null) {
                depth++;
                cursor = byId.get(cursor).getParentId();
            }
            assertThat(depth).isLessThanOrEqualTo(2);
            assertThat(depth).isLessThanOrEqualTo(Menu.MAX_DEPTH);
        }
    }

    @Test
    @DisplayName("링크 노드는 모두 '/' 로 시작하는 경로를 갖는다")
    void everyLinkHasAbsolutePath() {
        assertThat(adapter.findAll())
                .filteredOn(m -> m.getType() != MenuType.DIVIDER)
                .allSatisfy(m -> assertThat(m.getPath()).startsWith("/"));
    }

    @Test
    @DisplayName("구매자 메뉴 6개는 USER 에게만 보인다 — 관리자 네비에는 주문/추천/대량주문/결제/잔액/알림이 없었다")
    void shopMenusAreUserOnly() {
        Set<String> shopNames = adapter.findAll().stream()
                .filter(m -> m.getArea() == MenuArea.SHOP)
                .map(Menu::getName)
                .collect(Collectors.toSet());

        assertThat(shopNames)
                .containsExactlyInAnyOrder("주문하기", "추천받기", "대량주문", "나눠 결제", "내 포인트·상품권", "내 알림");
        assertThat(adapter.findAll()).filteredOn(m -> m.getArea() == MenuArea.SHOP)
                .allSatisfy(m -> assertThat(m.allowedRoles()).containsExactly("USER"));
    }
}
