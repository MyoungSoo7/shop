package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.order.application.port.out.LoadSalesStatsPort.SalesCriteria;
import github.lms.lemuel.order.domain.CategorySales;
import github.lms.lemuel.order.domain.ProductSales;
import github.lms.lemuel.order.domain.SalesTotal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판매 집계 SQL 회귀 테스트 — 실 PostgreSQL.
 *
 * <p><b>왜 단위 테스트로는 안 되는가</b>: 이 기능의 정확성은 전부 SQL 안에 있다. 취소된 라인을
 * 빼는 것, 쿠폰 안분액을 빼는 것, 대표 분류 하나로만 세는 것, 대표 분류가 없는 상품을 남기는 것 —
 * 어느 하나가 무너져도 서비스 계층은 <b>여전히 정상적으로 동작한다</b>. 숫자만 조용히 틀린다.
 * 목으로 감싼 테스트는 그 네 가지를 하나도 보지 못한다.
 *
 * <p>H2 로 대체하지 않는 이유는 {@link OrderHistoryQueryIT} 와 같다 — 부분 유니크 인덱스,
 * {@code ARRAY_AGG(… ORDER BY …)}, {@code NULLS LAST} 는 방언이 갈리는 지점이라 여기서 통과해도
 * 운영 DB 에서 같은 답이 나온다는 보장이 없다.
 *
 * <h2>고정 데이터</h2>
 * 기간은 2026-08-01 ~ 2026-08-31(반열림으로 9/1 00:00), 상태는 {@code PAID} 하나로 고정한다.
 * <pre>
 *   상품  P1(대표=A)          P2(대표=A, 추가=B)   P3(분류 없음)
 *   O1 PAID  8/10  P1 x2 20,000 -2,000 = 18,000 | P2 x1 50,000 = 50,000
 *   O2 PAID  8/11  P3 x3 30,000 = 30,000        | P1 x1 10,000 (취소됨 — 빠져야 한다)
 *   O5 PAID  8/12  P1 x1  5,000 = 5,000
 *   O3 CREATED 8/10  P1 x5 100,000  (상태 밖 — 빠져야 한다)
 *   O4 PAID    7/01  P1 x7 700,000  (기간 밖 — 빠져야 한다)
 * </pre>
 * 합계는 수량 7 · 순액 103,000 · 라인 4 · 주문 3 이다. 이 숫자 하나하나가 위 배제 규칙 중
 * 정확히 하나씩에 대응한다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxSchema.class)
@ActiveProfiles("test")
class SalesStatsQueryIT {

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

    private static final long P1 = 910_001L;
    private static final long P2 = 910_002L;
    private static final long P3 = 910_003L;
    private static final long CAT_A = 920_001L;
    private static final long CAT_B = 920_002L;

    private static final SalesCriteria PAID_AUGUST = new SalesCriteria(
            List.of("PAID"),
            LocalDateTime.of(2026, 8, 1, 0, 0),
            LocalDateTime.of(2026, 9, 1, 0, 0));

    @PersistenceContext
    EntityManager em;

    @Autowired
    DataSource dataSource;

    private SalesStatsQueryJdbcAdapter adapter;

    @BeforeEach
    void seed() {
        adapter = new SalesStatsQueryJdbcAdapter(new JdbcTemplate(dataSource));

        // 상품명이 바뀐 이력을 만든다. 'v2'(옛것) 가 'v10'(새것) 보다 사전순으로 크다 —
        // MAX(product_name) 로 뽑으면 옛 이름이 나오고, 시간순으로 뽑아야 새 이름이 나온다.
        insertProduct(P1, "상품A-v10");
        insertProduct(P2, "상품B");
        insertProduct(P3, "상품C");

        insertCategory(CAT_A, "가전", "home-appliance");
        insertCategory(CAT_B, "기획전", "promotion");

        insertMapping(P1, CAT_A, true);
        insertMapping(P2, CAT_A, true);
        // P2 는 기획전에도 걸려 있다. 대표가 아니므로 집계에 두 번 들어가면 안 된다.
        insertMapping(P2, CAT_B, false);

        long o1 = insertOrder("PAID", "2026-08-10T10:00:00");
        insertItem(o1, P1, "상품A-v2", 2, "20000", "2000", "2026-08-10T10:00:00", null);
        insertItem(o1, P2, "상품B", 1, "50000", "0", "2026-08-10T10:00:00", null);

        long o2 = insertOrder("PAID", "2026-08-11T10:00:00");
        insertItem(o2, P3, "상품C", 3, "30000", "0", "2026-08-11T10:00:00", null);
        insertItem(o2, P1, "상품A-v2", 1, "10000", "0", "2026-08-11T10:00:00", "2026-08-11T11:00:00");

        long o5 = insertOrder("PAID", "2026-08-12T10:00:00");
        insertItem(o5, P1, "상품A-v10", 1, "5000", "0", "2026-08-12T10:00:00", null);

        long o3 = insertOrder("CREATED", "2026-08-10T09:00:00");
        insertItem(o3, P1, "상품A-v2", 5, "100000", "0", "2026-08-10T09:00:00", null);

        long o4 = insertOrder("PAID", "2026-07-01T10:00:00");
        insertItem(o4, P1, "상품A-v2", 7, "700000", "0", "2026-07-01T10:00:00", null);

        em.flush();
    }

    // ---------------------------------------------------------------- 합계

    @Test
    @DisplayName("합계는 취소 라인·상태 밖·기간 밖을 모두 뺀다")
    void 합계는_배제규칙_셋을_모두_적용한다() {
        SalesTotal total = adapter.total(PAID_AUGUST);

        assertThat(total.quantity()).isEqualTo(7);          // 2 + 3 + 1 + 1
        assertThat(total.netAmount()).isEqualByComparingTo("103000");
        assertThat(total.lineCount()).isEqualTo(4);
        assertThat(total.orderCount()).isEqualTo(3);        // O1 · O2 · O5
    }

    @Test
    @DisplayName("순액은 쿠폰 안분액을 뺀 값 — line_amount 만 더하면 105,000 이 된다")
    void 순액은_안분액을_뺀다() {
        assertThat(adapter.total(PAID_AUGUST).netAmount()).isEqualByComparingTo("103000");
    }

    @Test
    @DisplayName("한 건도 없으면 NULL 이 아니라 0 — 빈 칸은 '실패'처럼 보인다")
    void 결과가_없으면_0() {
        SalesTotal total = adapter.total(new SalesCriteria(
                List.of("PAID"),
                LocalDateTime.of(2020, 1, 1, 0, 0),
                LocalDateTime.of(2020, 2, 1, 0, 0)));

        assertThat(total.netAmount()).isEqualByComparingTo("0");
        assertThat(total.quantity()).isZero();
        assertThat(total.lineCount()).isZero();
        assertThat(total.orderCount()).isZero();
    }

    @Test
    @DisplayName("상태 조건이 비면 던진다 — '전부'로 넓어지면 미결제·환불이 매출로 들어온다")
    void 상태조건_없이는_집계하지_않는다() {
        assertThatThrownBy(() -> adapter.total(new SalesCriteria(
                List.of(), PAID_AUGUST.createdFrom(), PAID_AUGUST.createdToExclusive())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기간은 반열림 — 종료 경계 당일 주문이 빠지지 않는다")
    void 기간은_반열림이다() {
        // 8/12 하루만 본다. createdToExclusive 가 8/12 였다면 O5 가 통째로 빠진다.
        SalesTotal total = adapter.total(new SalesCriteria(
                List.of("PAID"),
                LocalDateTime.of(2026, 8, 12, 0, 0),
                LocalDateTime.of(2026, 8, 13, 0, 0)));

        assertThat(total.netAmount()).isEqualByComparingTo("5000");
    }

    // ---------------------------------------------------------------- 랭킹

    @Test
    @DisplayName("랭킹은 순액 내림차순")
    void 랭킹은_순액_내림차순() {
        List<ProductSales> rows = adapter.topProducts(PAID_AUGUST, 10);

        assertThat(rows).extracting(ProductSales::productId).containsExactly(P2, P3, P1);
        assertThat(rows).extracting(ProductSales::netAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("50000"), new BigDecimal("30000"), new BigDecimal("23000"));
    }

    @Test
    @DisplayName("주문수는 라인 수가 아니라 주문 수 — P1 은 라인 2개지만 주문 2건")
    void 주문수는_주문_단위() {
        ProductSales p1 = product(adapter.topProducts(PAID_AUGUST, 10), P1);

        assertThat(p1.quantity()).isEqualTo(3);       // 2 + 1 (취소 라인 제외)
        assertThat(p1.orderCount()).isEqualTo(2);     // O1 · O5
    }

    @Test
    @DisplayName("상품명은 기간 안에서 가장 최근 스냅샷 — 사전순 최대값이 아니다")
    void 상품명은_최근_스냅샷() {
        ProductSales p1 = product(adapter.topProducts(PAID_AUGUST, 10), P1);

        // MAX(product_name) 이었다면 '상품A-v2'('v2' > 'v10') 가 나온다.
        assertThat(p1.productName()).isEqualTo("상품A-v10");
    }

    @Test
    @DisplayName("limit 은 행만 자르고 합계는 건드리지 않는다")
    void limit은_합계를_건드리지_않는다() {
        List<ProductSales> rows = adapter.topProducts(PAID_AUGUST, 2);

        assertThat(rows).hasSize(2);
        assertThat(adapter.total(PAID_AUGUST).netAmount()).isEqualByComparingTo("103000");
    }

    // ---------------------------------------------------------------- 카테고리

    @Test
    @DisplayName("대표 분류 하나로만 센다 — 추가 분류(기획전)는 나타나지 않는다")
    void 대표분류로만_센다() {
        List<CategorySales> rows = adapter.byCategory(PAID_AUGUST);

        assertThat(rows).extracting(CategorySales::categoryId).doesNotContain(CAT_B);
        assertThat(category(rows, CAT_A).netAmount()).isEqualByComparingTo("73000");   // P1 23,000 + P2 50,000
    }

    @Test
    @DisplayName("대표 분류가 없는 상품은 미분류 한 줄로 남는다 — 사라지면 합계가 조용히 작아진다")
    void 미분류가_남는다() {
        List<CategorySales> rows = adapter.byCategory(PAID_AUGUST);

        List<CategorySales> unclassified = rows.stream().filter(CategorySales::unclassified).toList();
        assertThat(unclassified).hasSize(1);
        assertThat(unclassified.get(0).netAmount()).isEqualByComparingTo("30000");
        assertThat(unclassified.get(0).categoryName()).isNull();
        assertThat(unclassified.get(0).depth()).isNull();
    }

    @Test
    @DisplayName("잘라내기가 없으므로 행 합이 전체 합과 정확히 같다 — 중복 계산·누락의 유일한 관측점")
    void 행합은_전체합과_같다() {
        List<CategorySales> rows = adapter.byCategory(PAID_AUGUST);

        BigDecimal rowSum = rows.stream()
                .map(CategorySales::netAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(rowSum).isEqualByComparingTo(adapter.total(PAID_AUGUST).netAmount());
    }

    @Test
    @DisplayName("분류 메타(경로·깊이)가 함께 온다")
    void 분류_메타가_온다() {
        CategorySales a = category(adapter.byCategory(PAID_AUGUST), CAT_A);

        assertThat(a.categoryName()).isEqualTo("가전");
        assertThat(a.pathSlug()).isEqualTo("home-appliance");
        assertThat(a.depth()).isZero();
    }

    // ---------------------------------------------------------------- 시드 도우미

    private static ProductSales product(List<ProductSales> rows, long productId) {
        return rows.stream().filter(r -> r.productId() == productId).findFirst().orElseThrow();
    }

    private static CategorySales category(List<CategorySales> rows, long categoryId) {
        return rows.stream()
                .filter(r -> r.categoryId() != null && r.categoryId() == categoryId)
                .findFirst().orElseThrow();
    }

    private void insertProduct(long id, String name) {
        em.createNativeQuery("""
                INSERT INTO opslab.products(id, name, price, stock_quantity, status)
                VALUES (?1, ?2, 1000, 100, 'ACTIVE')
                """)
                .setParameter(1, id)
                .setParameter(2, name)
                .executeUpdate();
    }

    private void insertCategory(long id, String name, String slug) {
        em.createNativeQuery("""
                INSERT INTO opslab.ecommerce_categories(id, name, slug, parent_id, depth, sort_order,
                                                        is_active, path_ids, path_slug)
                VALUES (?1, ?2, ?3, NULL, 0, 0, TRUE, ARRAY[?1]::BIGINT[], ?3)
                """)
                .setParameter(1, id)
                .setParameter(2, name)
                .setParameter(3, slug)
                .executeUpdate();
    }

    private void insertMapping(long productId, long categoryId, boolean primary) {
        em.createNativeQuery("""
                INSERT INTO opslab.product_ecommerce_categories(product_id, category_id, is_primary)
                VALUES (?1, ?2, ?3)
                """)
                .setParameter(1, productId)
                .setParameter(2, categoryId)
                .setParameter(3, primary)
                .executeUpdate();
    }

    private long orderSeq = 930_000L;

    private long insertOrder(String status, String createdAt) {
        long id = ++orderSeq;
        em.createNativeQuery("""
                INSERT INTO opslab.orders(id, user_id, amount, status, shipping_fee, shipped,
                                          created_at, updated_at)
                VALUES (?1, 1, 0, ?2, 0, false, CAST(?3 AS timestamp), CAST(?3 AS timestamp))
                """)
                .setParameter(1, id)
                .setParameter(2, status)
                .setParameter(3, createdAt)
                .executeUpdate();
        return id;
    }

    private void insertItem(long orderId, long productId, String productName, int quantity,
                            String lineAmount, String allocatedDiscount, String createdAt,
                            String canceledAt) {
        em.createNativeQuery("""
                INSERT INTO opslab.order_items(order_id, product_id, product_name, unit_price, quantity,
                                               line_amount, allocated_discount, created_at, canceled_at)
                VALUES (?1, ?2, ?3, CAST(?4 AS numeric) / ?5, ?5, CAST(?4 AS numeric),
                        CAST(?6 AS numeric), CAST(?7 AS timestamp), CAST(?8 AS timestamp))
                """)
                .setParameter(1, orderId)
                .setParameter(2, productId)
                .setParameter(3, productName)
                .setParameter(4, lineAmount)
                .setParameter(5, quantity)
                .setParameter(6, allocatedDiscount)
                .setParameter(7, createdAt)
                .setParameter(8, canceledAt)
                .executeUpdate();
    }
}
