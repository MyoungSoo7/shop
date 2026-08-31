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
    @DisplayName("시드 총 57행 — 커머스 + 운영 + 파트너 + 셀러 범위")
    void seedsExactlyFiftySeven() {
        // 50 → 53 은 V20260828210000 의 세 행이다: '파트너 콘솔' GROUP 하나와
        // 그 자식 '매출 대시보드' · '주문 내역'. 이 숫자를 고정해 두는 이유는 시드가
        // 조용히 늘어나는 걸 잡기 위해서다 — 늘어난 이유를 여기 적지 않으면 다음 사람은
        // 숫자만 고쳐 통과시키고, 그러면 이 검사는 아무것도 지키지 않게 된다.
        //
        // 53 → 57 은 V20260901100000 의 네 행이다: '셀러 콘솔' GROUP 하나와 그 자식
        // '상품 등록' · '주문 · 출고', 그리고 '시스템 관리' 아래의 '상품 심사'. 심사가
        // 셀러 콘솔 밖에 있는 이유는 systemChildren() 쪽에 적었다.
        assertThat(adapter.findAll()).hasSize(57);
    }

    @Test
    @DisplayName("최상위 21개가 상단 네비 순서대로 들어간다")
    void rootsInOrder() {
        List<Menu> roots = adapter.findAll().stream()
                .filter(m -> m.getParentId() == null)
                .sorted(java.util.Comparator.comparingInt(Menu::getSortOrder))
                .toList();

        assertThat(roots).extracting(Menu::getName).containsExactly(
                // 상품관리는 사라진 '정산' 그룹이 쓰던 자리(1)로 올라왔다 — 대시보드 바로 뒤다.
                // '반품·교환'은 '승인' 의 자식이 아니라 바로 뒤 형제다 — '승인' 은 ITEM 이라
                // 자식을 붙이려면 GROUP 으로 바꿔야 하고, 그러면 그 링크로 들어가던 취소·환불
                // 승인 큐가 링크가 아니게 된다. 맨 뒤(MAX+1)가 아닌 이유는 프론트 폴백
                // (menuFallback.ts)이 같은 자리에 두기 때문이다 — 순서가 갈리면 서버가 죽어
                // 폴백이 뜨는 순간에야 그 사실이 드러난다.
                // '문의 응대'도 같은 이유로 '승인' 의 형제이고, 자리는 '반품·교환' 바로 뒤다.
                // 시드가 뒤를 한 칸 밀 때 area 로 좁히지 않는 것이 중요하다 — '시스템 관리' 는
                // area 가 SYSTEM 이라 BACKOFFICE 로 걸러 내면 밀리지 않고, 그러면 새 행과
                // sort_order 가 겹쳐 둘의 순서가 정해지지 않는다.
                "대시보드", "상품관리", "배송", "승인", "반품·교환", "문의 응대", "시스템 관리",
                // 대량주문은 관리자 기능이 아니라 구매자가 자기 주문을 올리는 경로다 — SHOP 최상위.
                // 나눠 결제는 주문(20)과 잔액 확인(30) 사이 — 주문에서 결제로 이어지는 순서다.
                // 내 알림(35)은 내 포인트·상품권(30) 다음 — 둘 다 "내 것"을 보는 개인 화면이다.
                // 내 문의(40)는 그 뒤. 경로가 /inquiries 가 아니라 /my/inquiries 인 것은 nginx
                // 두 벌이 inquiries 세그먼트를 게이트웨이로 프록시하기 때문이다 — 같은 이름의
                // SPA 라우트를 두면 새로고침에서 목록 JSON 이 그대로 렌더된다.
                // 여러 곳 배송(45)은 맨 뒤다. 앞의 셋과 달리 뒤를 밀지 않는 이유는 끼어들 자리가
                // 없기 때문이다 — SHOP 최상위는 7·8·20·25·30·35·40 으로 차 있고 이 항목은 그 뒤다.
                // 배송지 주소록(50)도 같은 이유로 그 뒤에 붙는다. 경로가 /addresses 가 아니라
                // /my/addresses 인 것은 내 문의와 같은 이유다 — 세그먼트가 게이트웨이로 프록시되는
                // 이름과 겹치면 새로고침에서 JSON 이 그대로 뜬다.
                // 포인트 선물(55)도 그 뒤다. '내 포인트·상품권'(30) 옆이 어울리지만, 그 자리에
                // 끼우려면 뒤를 전부 밀어야 하고 그 UPDATE 는 이미 배포된 sort_order 를 건드린다.
                // 카테고리 탐색(60)도 그 뒤다. 자리는 '주문하기' 앞이 어울린다 — 둘러보고 고르는
                // 순서라서 — 하지만 앞자리를 만들려면 이미 배포된 sort_order 를 전부 밀어야 한다.
                // 경로가 /categories 가 아니라 /browse 인 것은 내 문의와 같은 이유다.
                // 이벤트(65, V20260828190000)도 맨 뒤다 — 이유는 카테고리 탐색과 같다. 이 행은
                // 화면만 이 SPA 에 있고 부르는 API 는 marketing-service 것이다(ADR 0045).
                // 메뉴 원장은 order-service 의 menus 한 벌뿐이라 행 자체는 여기 있다.
                "주문하기", "추천받기", "대량주문", "나눠 결제", "내 포인트·상품권", "내 알림", "내 문의",
                "여러 곳 배송", "배송지 주소록", "포인트 선물", "카테고리 탐색", "이벤트",
                // 파트너 콘솔(70, V20260828210000)이 맨 뒤다. 앞의 것들과 달리 area 가 CORP 라
                // 여태 행이 하나도 없던 영역의 첫 행인데, 그래도 이 목록에 들어온다 — 상단 네비는
                // area 로 거르지 않고 최상위를 전부 그린다. area 는 표시 필터가 아니라 분류다.
                // required_role 이 'USER' 인 것도 실수가 아니다. 이 저장소의 메뉴 역할 어휘는
                // ADMIN · MANAGER · USER 뿐이고 PARTNER 가 없어서, 'PARTNER' 라고 적으면 서버가
                // 모르는 값이라 아무도 안 걸리는데 원장에는 통제가 있는 것처럼 남는다. 진짜 차단은
                // partner-service 한 곳에서만 한다 — 토큰의 회원번호로 조직을 못 찾으면 403.
                // 그래서 이 행이 늘린 것은 권한이 아니라 노출이다.
                "파트너 콘솔",
                // 셀러 콘솔(75, V20260901100000)이 그 뒤다. area 는 SELLER — enum 에는 있었는데
                // 행이 하나도 없던 영역의 첫 행이다. 파는 쪽(SELLER)과 사는 기업(CORP)을 같은
                // 영역에 넣지 않는 것이 목적이고, 표시 여부와는 무관하다.
                // required_role 'USER' 도 파트너와 같은 이유다 — SELLER 라는 역할 어휘가 없다.
                // 진짜 차단은 seller-service 한 곳: 조직을 못 찾으면 403 NOT_A_SELLER_MEMBER,
                // 조직은 맞는데 파는 쪽이 아니면 422 NOT_A_SELLER_ORG.
                "셀러 콘솔");
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
    @DisplayName("시스템 사이드바 30개 — 앞 3개와 게시판 관리가 RBAC permission 과 짝지어진다")
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
                "리뷰 관리", "쿠폰 운영", "환불 운영", "셀러 등급",
                // dentis 관리자 콘솔에서 옮겨 온 4종(V20260826150000). 앞의 셋은 "보기만 하는" 표면이고
                // 작업 큐만 MANAGER 에게도 열린다 — 밀린 주문을 실제로 처리하는 쪽이라서다.
                "권한 계정", "지표 추이", "판매 통계", "작업 큐",
                // 수강 신청(V20260827110000)은 '교육 관리'(과정·차시)의 짝이지만 자리는 맨 뒤다 —
                // 이 그룹의 sort_order 는 빈틈없이 차 있어 중간에 끼우면 그 자리의 항목과 겹친다.
                // 강사 관리(V20260827130000)도 같은 이유로 그 뒤에 이어 붙는다.
                // 팝업 관리(V20260827150000)는 교육이 아니라 사이트 콘텐츠지만 자리는 역시 맨 뒤다.
                // 댓글 관리(V20260827170000)는 '게시판 관리'(게시판 정의)와 다른 표면이다 — 이미 달린
                // 댓글을 게시판·글을 건너뛰고 훑는 유일한 자리. 자리는 역시 맨 뒤.
                // 동의 이력(V20260827210000)도 맨 뒤다 — 감사 로그 옆이 어울리지만 그 자리의
                // sort_order 가 차 있다. 읽기 전용이라 고치는 버튼이 없고, 그래서 '운영'이 아니라 '이력'이다.
                // 상품 옵션(V20260828180000)도 맨 뒤다 — '옵션 카탈로그' 바로 옆이 어울리지만
                // 그 자리의 sort_order 가 차 있다. 이름이 비슷해도 다른 표면이다: 카탈로그는
                // 축·값 사전이고 이쪽은 상품별 실물 SKU(재고·추가금)다.
                // 이벤트 프로모션(V20260828190000)도 맨 뒤다. 이 그룹에서 유일하게 부르는 API 가
                // order-service 것이 아니다 — marketing-service(8096) 다(ADR 0045). 그래도 행이
                // 여기 있는 것은 메뉴 원장이 order-service 의 menus 한 벌뿐이기 때문이다.
                // 상품 심사(V20260901100000)도 맨 뒤다. 이 그룹에서 두 번째로 부르는 API 가
                // order-service 것이 아닌 행이다 — seller-service(8104) 다. 셀러 콘솔 그룹이
                // 아니라 여기 있는 이유는 대상이 "내 조직"이 아니라 전체 신청서여서다. 셀러
                // 콘솔에 넣으면 그 그룹의 required_role 이 'USER,ADMIN' 이 되어야 하는데,
                // Menu.isAccessibleBy 는 정확 일치라서 운영자에게 자기가 403 을 받는 링크
                // (/seller/products)가 함께 그려진다. 환불 운영·셀러 등급도 같은 이유로 여기 있다.
                "수강 신청", "강사 관리", "팝업 관리", "댓글 관리", "동의 이력", "상품 옵션", "이벤트 프로모션",
                "상품 심사");
        assertThat(children).extracting(Menu::getRequiredPermission).containsExactly(
                "SYSTEM_MENU_MANAGE", "SYSTEM_CODE_MANAGE", "SYSTEM_RBAC_MANAGE",
                null, null, null, null, "SYSTEM_BOARD_MANAGE", null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null);
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
        // 작업 큐는 환불 운영과 같은 이유로 등급이 낮다 — 서버가 /admin/order-queues 를 ADMIN·MANAGER 로
        // 막는다. 메뉴만 ADMIN 으로 좁히면 실제로 밀린 주문을 처리하는 MANAGER 가 화면을 못 찾는다.
        assertThat(children.stream().filter(m -> m.getName().equals("작업 큐"))
                .findFirst().orElseThrow().allowedRoles())
                .containsExactlyInAnyOrder("ADMIN", "MANAGER");
        // 동의 이력도 같은 이유로 MANAGER 까지 열린다 — 서버가 /admin/privacy-consents 를 ADMIN·MANAGER
        // 로 막는다. 화면 URL 은 API 경로와 달라야 한다(같으면 새로고침이 JSON 을 렌더한다).
        Menu privacyConsents = children.stream().filter(m -> m.getName().equals("동의 이력"))
                .findFirst().orElseThrow();
        assertThat(privacyConsents.allowedRoles()).containsExactlyInAnyOrder("ADMIN", "MANAGER");
        assertThat(privacyConsents.getPath()).isEqualTo("/admin/system/privacy-consents");
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
    @DisplayName("구매자 메뉴 12개는 USER 에게만 보인다 — 관리자 네비에는 주문/추천/대량주문/결제/잔액/알림/문의/여러곳배송/주소록/포인트선물/카테고리탐색/이벤트가 없었다")
    void shopMenusAreUserOnly() {
        Set<String> shopNames = adapter.findAll().stream()
                .filter(m -> m.getArea() == MenuArea.SHOP)
                .map(Menu::getName)
                .collect(Collectors.toSet());

        assertThat(shopNames)
                .containsExactlyInAnyOrder(
                        "주문하기", "추천받기", "대량주문", "나눠 결제", "내 포인트·상품권", "내 알림", "내 문의",
                        "여러 곳 배송", "배송지 주소록", "포인트 선물", "카테고리 탐색", "이벤트");
        assertThat(adapter.findAll()).filteredOn(m -> m.getArea() == MenuArea.SHOP)
                .allSatisfy(m -> assertThat(m.allowedRoles()).containsExactly("USER"));
    }
}
