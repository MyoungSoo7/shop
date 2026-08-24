package github.lms.lemuel.product.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Product 도메인의 생성자/세터/상태 확인 메서드 등, ProductFullTest 에서 다루지 않는 나머지 라인 보강.
 */
class ProductExtraTest {

    @Test
    @DisplayName("기본 생성자: 초기값 확인")
    void noArgConstructor() {
        Product p = Product.create("상품", "설명", new BigDecimal("100"), 0);

        assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(p.getStockQuantity()).isZero();
        assertThat(p.getTagIds()).isEmpty();
        assertThat(p.getCreatedAt()).isNotNull();
        assertThat(p.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("전체 생성자: null 값들은 기본값으로 대체")
    void fullConstructor_defaultsForNulls() {
        Product p = Product.rehydrate(1L, "상품", "설명", new BigDecimal("100"),
                null, null, 5L, null, null, null, null);

        assertThat(p.getStockQuantity()).isZero();
        assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(p.getTagIds()).isEmpty();
        assertThat(p.getCreatedAt()).isNotNull();
        assertThat(p.getUpdatedAt()).isNotNull();
        assertThat(p.getCategoryId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("전체 생성자: 값이 모두 있으면 그대로 보존")
    void fullConstructor_preservesValues() {
        LocalDateTime now = LocalDateTime.now();
        Product p = Product.rehydrate(1L, "상품", "설명", new BigDecimal("100"), 10,
                ProductStatus.INACTIVE, 5L, List.of(1L, 2L), "{}", now, now);

        assertThat(p.getStockQuantity()).isEqualTo(10);
        assertThat(p.getStatus()).isEqualTo(ProductStatus.INACTIVE);
        assertThat(p.getTagIds()).containsExactly(1L, 2L);
        assertThat(p.getOptionsJson()).isEqualTo("{}");
        assertThat(p.getCreatedAt()).isEqualTo(now);
        assertThat(p.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("create(...optionsJson): blank 문자열이면 null 로 저장")
    void create_withBlankOptionsJson() {
        Product p = Product.create("상품", "설명", new BigDecimal("100"), 1, "   ");

        assertThat(p.getOptionsJson()).isNull();
    }

    @Test
    @DisplayName("create(...optionsJson): 값이 있으면 그대로 저장")
    void create_withOptionsJson() {
        Product p = Product.create("상품", "설명", new BigDecimal("100"), 1, "{\"a\":1}");

        assertThat(p.getOptionsJson()).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("rehydrate: categoryId / optionsJson 값 반영")
    void setters_updateTimestamp() {
        Product p = Product.rehydrate(1L, "상품", "설명", new BigDecimal("100"), 1,
                ProductStatus.ACTIVE, 7L, null, "{\"x\":true}", null, null);

        assertThat(p.getCategoryId()).isEqualTo(7L);
        assertThat(p.getOptionsJson()).isEqualTo("{\"x\":true}");
    }

    @Test
    @DisplayName("hasStock: 재고 있음/없음")
    void hasStock() {
        Product p = Product.create("상품", "설명", new BigDecimal("100"), 3);
        assertThat(p.hasStock()).isTrue();

        p.decreaseStock(3);
        assertThat(p.hasStock()).isFalse();
    }

    @Test
    @DisplayName("isActive: ACTIVE 상태일 때만 true")
    void isActive() {
        Product p = Product.create("상품", "설명", new BigDecimal("100"), 3);
        assertThat(p.isActive()).isTrue();

        p.deactivate();
        assertThat(p.isActive()).isFalse();
    }

    @Test
    @DisplayName("isDiscontinued: 단종 전에는 false")
    void isDiscontinued_falseBeforeDiscontinue() {
        Product p = Product.create("상품", "설명", new BigDecimal("100"), 3);
        assertThat(p.isDiscontinued()).isFalse();
    }

    @Test
    @DisplayName("updateInfo: name 이 null 이면 이름 변경 없음")
    void updateInfo_nullNameKeepsOriginal() {
        Product p = Product.create("원래", "desc", new BigDecimal("100"), 1);

        p.updateInfo(null, "새설명");

        assertThat(p.getName()).isEqualTo("원래");
        assertThat(p.getDescription()).isEqualTo("새설명");
    }

    @Test
    @DisplayName("updateInfo: description 이 null 이면 설명 변경 없음")
    void updateInfo_nullDescriptionKeepsOriginal() {
        Product p = Product.create("원래", "desc", new BigDecimal("100"), 1);

        p.updateInfo("변경", null);

        assertThat(p.getName()).isEqualTo("변경");
        assertThat(p.getDescription()).isEqualTo("desc");
    }

    @Test
    @DisplayName("rehydrate: 전 필드 복원 + 상태 파생")
    void directSetters() {
        LocalDateTime now = LocalDateTime.now();
        Product p = Product.rehydrate(9L, "이름", "설명", new BigDecimal("500"), 3,
                ProductStatus.DISCONTINUED, null, null, null, now, now);

        assertThat(p.getId()).isEqualTo(9L);
        assertThat(p.getName()).isEqualTo("이름");
        assertThat(p.getDescription()).isEqualTo("설명");
        assertThat(p.getPrice()).isEqualByComparingTo("500");
        assertThat(p.getStockQuantity()).isEqualTo(3);
        assertThat(p.getStatus()).isEqualTo(ProductStatus.DISCONTINUED);
        assertThat(p.isDiscontinued()).isTrue();
        assertThat(p.getCreatedAt()).isEqualTo(now);
        assertThat(p.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("getTagIds: 반환된 리스트는 방어적 복사본 (외부 변경 무영향)")
    void getTagIds_defensiveCopy() {
        Product p = Product.create("상품", "설명", new BigDecimal("100"), 1);
        p.addTag(1L);

        List<Long> tags = p.getTagIds();
        tags.add(999L);

        assertThat(p.getTagIds()).doesNotContain(999L);
    }

    @Test
    @DisplayName("setTagIds: null 이면 빈 리스트로 초기화")
    void setTagIds_null() {
        Product p = Product.create("상품", "설명", new BigDecimal("100"), 1);
        p.addTag(1L);

        p.replaceTags(null);

        assertThat(p.getTagIds()).isEmpty();
    }

    @Test
    @DisplayName("setTagIds: 값이 있으면 복사되어 반영")
    void setTagIds_withValues() {
        Product p = Product.create("상품", "설명", new BigDecimal("100"), 1);

        p.replaceTags(List.of(1L, 2L, 3L));

        assertThat(p.getTagIds()).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("assignId: 두 번째 부여는 IllegalStateException (write-once)")
    void assignId_writeOnce() {
        Product p = Product.create("상품", "설명", new BigDecimal("100"), 1);
        p.assignId(1L);
        assertThatThrownBy(() -> p.assignId(2L)).isInstanceOf(IllegalStateException.class);
    }
}
