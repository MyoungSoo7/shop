package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductPersistenceAdapterTest {

    @Mock SpringDataProductJpaRepository repository;
    // 대표 분류 조회 — 스텁하지 않으면 빈 결과라 categoryId 는 null 로 남는다(이 테스트의 관심사 밖).
    @Mock github.lms.lemuel.product.application.port.out.LoadPrimaryCategoryPort loadPrimaryCategoryPort;
    @Mock ProductPersistenceMapper mapper;

    private ProductPersistenceAdapter adapter() {
        return new ProductPersistenceAdapter(repository, loadPrimaryCategoryPort, mapper);
    }

    private ProductJpaEntity entity(Long id) {
        ProductJpaEntity e = new ProductJpaEntity();
        e.setId(id);
        e.setName("상품" + id);
        e.setPrice(new BigDecimal("1000"));
        e.setStockQuantity(5);
        e.setStatus(ProductStatus.ACTIVE);
        return e;
    }

    @Test
    @DisplayName("save: 매퍼로 변환 후 저장하고 도메인으로 복원")
    void save() {
        Product product = Product.create("상품A", "desc", new BigDecimal("1000"), 5);
        ProductJpaEntity toSave = entity(null);
        ProductJpaEntity saved = entity(1L);
        when(mapper.toEntity(product)).thenReturn(toSave);
        when(repository.save(toSave)).thenReturn(saved);
        when(mapper.toDomain(saved)).thenReturn(product);

        Product result = adapter().save(product);

        assertThat(result).isSameAs(product);
        verify(repository).save(toSave);
    }

    @Test
    @DisplayName("findById: 존재하면 도메인 반환")
    void findById_found() {
        ProductJpaEntity e = entity(1L);
        Product domain = Product.create("상품1", "desc", new BigDecimal("1000"), 5);
        when(repository.findById(1L)).thenReturn(Optional.of(e));
        when(mapper.toDomain(e)).thenReturn(domain);

        assertThat(adapter().findById(1L)).contains(domain);
    }

    @Test
    @DisplayName("findById: 미존재면 empty")
    void findById_notFound() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThat(adapter().findById(1L)).isEmpty();
    }

    @Test
    @DisplayName("findByName: 위임")
    void findByName() {
        ProductJpaEntity e = entity(1L);
        Product domain = Product.create("상품1", "desc", new BigDecimal("1000"), 5);
        when(repository.findByName("상품1")).thenReturn(Optional.of(e));
        when(mapper.toDomain(e)).thenReturn(domain);

        assertThat(adapter().findByName("상품1")).contains(domain);
    }

    @Test
    @DisplayName("findAll: 리스트 매핑")
    void findAll() {
        ProductJpaEntity e1 = entity(1L);
        ProductJpaEntity e2 = entity(2L);
        Product d1 = Product.create("상품1", "desc", new BigDecimal("1000"), 5);
        Product d2 = Product.create("상품2", "desc", new BigDecimal("2000"), 5);
        when(repository.findAll()).thenReturn(List.of(e1, e2));
        when(mapper.toDomain(e1)).thenReturn(d1);
        when(mapper.toDomain(e2)).thenReturn(d2);

        assertThat(adapter().findAll()).containsExactly(d1, d2);
    }

    @Test
    @DisplayName("findByStatus: 상태별 조회 매핑")
    void findByStatus() {
        ProductJpaEntity e = entity(1L);
        Product d = Product.create("상품1", "desc", new BigDecimal("1000"), 5);
        when(repository.findByStatus(ProductStatus.ACTIVE)).thenReturn(List.of(e));
        when(mapper.toDomain(e)).thenReturn(d);

        assertThat(adapter().findByStatus(ProductStatus.ACTIVE)).containsExactly(d);
    }

    @Test
    @DisplayName("findAvailableProducts: 판매가능 상품 매핑")
    void findAvailableProducts() {
        ProductJpaEntity e = entity(1L);
        Product d = Product.create("상품1", "desc", new BigDecimal("1000"), 5);
        when(repository.findAvailableProducts()).thenReturn(List.of(e));
        when(mapper.toDomain(e)).thenReturn(d);

        assertThat(adapter().findAvailableProducts()).containsExactly(d);
    }

    @Test
    @DisplayName("search: 키워드 trim + DESC 정렬(가격 내림차순)")
    void search_desc() {
        ProductJpaEntity cheap = entity(1L);
        ProductJpaEntity expensive = entity(2L);
        Product cheapDomain = Product.create("싼상품", "desc", new BigDecimal("1000"), 5);
        Product expensiveDomain = Product.create("비싼상품", "desc", new BigDecimal("9000"), 5);
        when(repository.search("shoe", 3L)).thenReturn(List.of(cheap, expensive));
        when(mapper.toDomain(cheap)).thenReturn(cheapDomain);
        when(mapper.toDomain(expensive)).thenReturn(expensiveDomain);

        List<Product> result = adapter().search(" shoe ", 3L, "price", "DESC");

        assertThat(result).containsExactly(expensiveDomain, cheapDomain);
    }

    @Test
    @DisplayName("search: 키워드 blank 면 null 로 정규화, ASC 정렬(이름)")
    void search_blankKeyword_ascByName() {
        ProductJpaEntity a = entity(1L);
        ProductJpaEntity b = entity(2L);
        Product da = Product.create("banana", "desc", new BigDecimal("1000"), 5);
        Product db = Product.create("apple", "desc", new BigDecimal("2000"), 5);
        when(repository.search(eq((String) null), eq(3L))).thenReturn(List.of(a, b));
        when(mapper.toDomain(a)).thenReturn(da);
        when(mapper.toDomain(b)).thenReturn(db);

        List<Product> result = adapter().search("   ", 3L, "name", "ASC");

        assertThat(result).containsExactly(db, da);
    }

    @Test
    @DisplayName("search: 정렬키 미지정/기본은 id 기준")
    void search_defaultComparator_byId() {
        ProductJpaEntity e1 = entity(1L);
        ProductJpaEntity e2 = entity(2L);
        Product d1 = Product.create("상품1", "desc", new BigDecimal("1000"), 5);
        d1.assignId(1L);
        Product d2 = Product.create("상품2", "desc", new BigDecimal("2000"), 5);
        d2.assignId(2L);
        when(repository.search(null, null)).thenReturn(List.of(e2, e1));
        when(mapper.toDomain(e1)).thenReturn(d1);
        when(mapper.toDomain(e2)).thenReturn(d2);

        List<Product> result = adapter().search(null, null, "unknown", "ASC");

        assertThat(result).containsExactly(d1, d2);
    }

    @Test
    @DisplayName("search: latest/createdAt 정렬")
    void search_byCreatedAt() {
        ProductJpaEntity e1 = entity(1L);
        ProductJpaEntity e2 = entity(2L);
        Product old = Product.rehydrate(null, "old", "desc", new BigDecimal("1000"), 5,
                ProductStatus.ACTIVE, null, null, null,
                java.time.LocalDateTime.now().minusDays(1), java.time.LocalDateTime.now());
        Product recent = Product.rehydrate(null, "recent", "desc", new BigDecimal("2000"), 5,
                ProductStatus.ACTIVE, null, null, null,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        when(repository.search(null, null)).thenReturn(List.of(e1, e2));
        when(mapper.toDomain(e1)).thenReturn(old);
        when(mapper.toDomain(e2)).thenReturn(recent);

        List<Product> result = adapter().search(null, null, "createdAt", "ASC");

        assertThat(result).containsExactly(old, recent);
    }

    @Test
    @DisplayName("decreaseStockIfAvailable: repository 위임")
    void decreaseStockIfAvailable() {
        when(repository.decreaseStockIfAvailable(eq(1L), eq(3), any())).thenReturn(1);

        assertThat(adapter().decreaseStockIfAvailable(1L, 3)).isEqualTo(1);
    }

    @Test
    @DisplayName("increaseStock: repository 위임")
    void increaseStock() {
        when(repository.increaseStock(eq(1L), eq(3), any())).thenReturn(1);

        assertThat(adapter().increaseStock(1L, 3)).isEqualTo(1);
    }

    @Test
    @DisplayName("existsByName: repository 위임")
    void existsByName() {
        when(repository.existsByName("상품A")).thenReturn(true);

        assertThat(adapter().existsByName("상품A")).isTrue();
    }
}
