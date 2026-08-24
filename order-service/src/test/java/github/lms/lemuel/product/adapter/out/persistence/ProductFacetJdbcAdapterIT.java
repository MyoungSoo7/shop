package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.product.application.port.out.LoadProductFacetPort.FacetCount;
import github.lms.lemuel.product.application.service.BackfillOptionCatalogService;
import github.lms.lemuel.product.application.service.BackfillVariantSignatureService;
import github.lms.lemuel.product.domain.OptionFacetQuery;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.ProductVariantStatus;
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
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파셋 조회 SQL 의미 검증 — 실 PostgreSQL.
 *
 * <p>이 테스트가 지키는 것은 <b>축 간 AND 가 SKU 하나 안에서 성립한다</b>는 규칙이다.
 * 상품 단위로 AND 를 걸면 "빨강 SKU 와 L SKU 를 따로 가진 상품" 이 빨강+L 검색에 걸린다 —
 * 정작 그 조합은 살 수 없는데 결과에 나온다. 이 규칙은 SQL 의 GROUP BY 단위에 들어 있어
 * 가짜 포트로는 증명할 수 없다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration({FlywayAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductPersistenceAdapter.class, ProductPersistenceMapperImpl.class,
        github.lms.lemuel.category.adapter.out.persistence.PrimaryCategoryLookupAdapter.class,
        ProductVariantPersistenceAdapter.class, OptionCatalogPersistenceAdapter.class,
        VariantOptionMappingPersistenceAdapter.class, ProductFacetJdbcAdapter.class,
        OutboxSchema.class})
@ActiveProfiles("test")
class ProductFacetJdbcAdapterIT {

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

    @Autowired ProductPersistenceAdapter productAdapter;
    @Autowired ProductVariantPersistenceAdapter variantAdapter;
    @Autowired OptionCatalogPersistenceAdapter catalogAdapter;
    @Autowired VariantOptionMappingPersistenceAdapter mappingAdapter;
    @Autowired ProductFacetJdbcAdapter facetAdapter;

    /**
     * 파셋 조회는 JDBC 라 JPA 영속성 컨텍스트에 <b>머물러 있는</b> 쓰기를 보지 못한다.
     * 백필의 마지막 상품은 뒤따르는 JPQL 이 없어 자동 flush 트리거를 못 받으므로,
     * 여기서 명시적으로 내려보내지 않으면 "마지막 상품만 조회에 안 잡히는" 착시가 생긴다.
     */
    @PersistenceContext EntityManager em;

    private Long redAndL;      // 빨강/L 을 실제로 파는 상품
    private Long crossOnly;    // 빨강 SKU 와 L SKU 는 있지만 빨강 L 조합은 없는 상품
    private Long soldOut;      // 빨강/L 이 있지만 재고 0

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        redAndL = product("A-" + n);
        crossOnly = product("B-" + n);
        soldOut = product("C-" + n);

        variant(redAndL, "A1-" + n, "색상:빨강/사이즈:L", 5);
        variant(redAndL, "A2-" + n, "색상:파랑/사이즈:M", 5);

        variant(crossOnly, "B1-" + n, "색상:빨강/사이즈:M", 5);
        variant(crossOnly, "B2-" + n, "색상:파랑/사이즈:L", 5);

        variant(soldOut, "C1-" + n, "색상:빨강/사이즈:L", 0);

        // 실제 이관 순서대로 카탈로그·매핑·서명을 채운다.
        new BackfillOptionCatalogService(variantAdapter, catalogAdapter, catalogAdapter).backfillAll();
        new BackfillVariantSignatureService(variantAdapter, variantAdapter, catalogAdapter, mappingAdapter)
                .backfillAll();
        em.flush();
    }

    private Long product(String name) {
        return productAdapter.save(Product.create(name, "설명", new BigDecimal("10000"), 100)).getId();
    }

    private void variant(Long productId, String sku, String optionName, int stock) {
        variantAdapter.save(ProductVariant.rehydrate(null, productId, sku, optionName,
                BigDecimal.ZERO, null, null, stock, 0L,
                stock > 0 ? ProductVariantStatus.ACTIVE : ProductVariantStatus.OUT_OF_STOCK,
                LocalDateTime.now(), LocalDateTime.now()));
    }

    private List<Long> find(boolean availableOnly, String... tokens) {
        return facetAdapter.findProductIds(OptionFacetQuery.of(List.of(tokens)), null, availableOnly);
    }

    @Test
    @DisplayName("축 간 AND 는 SKU 하나 안에서 성립한다 — 빨강 SKU·L SKU 를 따로 가진 상품은 걸리지 않는다")
    void andHoldsWithinSingleVariant() {
        List<Long> matched = find(true, "색상:빨강", "사이즈:L");

        assertThat(matched).contains(redAndL);
        assertThat(matched).doesNotContain(crossOnly);
    }

    @Test
    @DisplayName("같은 축의 여러 값은 OR — 빨강 또는 파랑이면 둘 다 걸린다")
    void orWithinAxis() {
        List<Long> matched = find(true, "색상:빨강", "색상:파랑");

        assertThat(matched).contains(redAndL, crossOnly);
    }

    @Test
    @DisplayName("단일 축 필터는 그 값을 가진 상품을 모두 낸다")
    void singleAxis() {
        assertThat(find(true, "사이즈:L")).contains(redAndL, crossOnly);
        assertThat(find(true, "사이즈:M")).contains(redAndL, crossOnly);
    }

    @Test
    @DisplayName("품절 SKU 는 기본으로 빠진다 — 눌러도 못 사는 결과를 보여주지 않는다")
    void excludesSoldOutByDefault() {
        assertThat(find(true, "색상:빨강", "사이즈:L")).doesNotContain(soldOut);
        assertThat(find(false, "색상:빨강", "사이즈:L")).contains(soldOut);
    }

    @Test
    @DisplayName("필터가 없으면 옵션을 가진 상품을 모두 낸다")
    void emptyQueryReturnsAll() {
        assertThat(find(true)).contains(redAndL, crossOnly).doesNotContain(soldOut);
    }

    @Test
    @DisplayName("존재하지 않는 값으로 거르면 빈 결과다")
    void unknownValueYieldsNothing() {
        assertThat(find(true, "색상:초록")).isEmpty();
    }

    @Test
    @DisplayName("파셋 개수는 상품 수를 센다 — SKU 수가 아니다")
    void countsProductsNotVariants() {
        List<FacetCount> counts = facetAdapter.countFacets(
                OptionFacetQuery.empty(), null, true, "색상");

        assertThat(counts).extracting(FacetCount::valueCode).contains("빨강", "파랑");
        // 빨강 SKU 를 가진 상품은 A(빨강/L)·B(빨강/M) 둘 — 품절인 C 는 빠진다.
        assertThat(counts).filteredOn(c -> c.valueCode().equals("빨강"))
                .allSatisfy(c -> assertThat(c.productCount()).isGreaterThanOrEqualTo(2));
    }

    @Test
    @DisplayName("자기 축 선택을 뺀 개수 — 빨강을 고른 상태에서도 파랑이 남아 추가 선택이 가능하다")
    void siblingValuesRemainSelectable() {
        OptionFacetQuery selectedRed = OptionFacetQuery.of(List.of("색상:빨강"));

        List<FacetCount> colorCounts = facetAdapter.countFacets(
                selectedRed.without("색상"), null, true, "색상");

        assertThat(colorCounts).extracting(FacetCount::valueCode).contains("빨강", "파랑");
    }
}
