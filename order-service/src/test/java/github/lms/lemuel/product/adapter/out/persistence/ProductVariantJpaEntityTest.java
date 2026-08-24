package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.ProductVariantStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ProductVariantJpaEntityTest {

    @Test
    @DisplayName("기본(no-discount) 생성자는 discount 필드를 null 로 위임")
    void basicConstructor() {
        LocalDateTime now = LocalDateTime.now();
        ProductVariantJpaEntity entity = new ProductVariantJpaEntity(1L, 10L, "SKU-1", "색상:빨강",
                new BigDecimal("500"), 5, 0L, ProductVariantStatus.ACTIVE, now, now);

        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getProductId()).isEqualTo(10L);
        assertThat(entity.getSku()).isEqualTo("SKU-1");
        assertThat(entity.getOptionName()).isEqualTo("색상:빨강");
        assertThat(entity.getAdditionalPrice()).isEqualByComparingTo("500");
        assertThat(entity.getDiscountPrice()).isNull();
        assertThat(entity.getDiscountRate()).isNull();
        assertThat(entity.getStockQuantity()).isEqualTo(5);
        assertThat(entity.getVersion()).isEqualTo(0L);
        assertThat(entity.getStatus()).isEqualTo(ProductVariantStatus.ACTIVE);
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("전체 생성자는 discount 필드도 보존")
    void fullConstructor() {
        LocalDateTime now = LocalDateTime.now();
        ProductVariantJpaEntity entity = new ProductVariantJpaEntity(1L, 10L, "SKU-1", "색상:빨강",
                new BigDecimal("500"), new BigDecimal("50"), new BigDecimal("10"), 5, 2L,
                ProductVariantStatus.ACTIVE, now, now);

        assertThat(entity.getDiscountPrice()).isEqualByComparingTo("50");
        assertThat(entity.getDiscountRate()).isEqualByComparingTo("10");
        assertThat(entity.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("protected no-arg 생성자 (JPA 용)")
    void noArgConstructor() throws Exception {
        java.lang.reflect.Constructor<ProductVariantJpaEntity> ctor =
                ProductVariantJpaEntity.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ProductVariantJpaEntity entity = ctor.newInstance();

        assertThat(entity.getId()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("onCreate: createdAt/updatedAt 이 null 이면 now() 로 채워짐")
    void onCreate_fillsTimestampsWhenNull() throws Exception {
        java.lang.reflect.Constructor<ProductVariantJpaEntity> ctor =
                ProductVariantJpaEntity.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ProductVariantJpaEntity entity = ctor.newInstance();

        java.lang.reflect.Method onCreate = ProductVariantJpaEntity.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(entity);

        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("onCreate: 이미 값이 있으면 덮어쓰지 않음")
    void onCreate_keepsExistingTimestamps() throws Exception {
        LocalDateTime fixed = LocalDateTime.of(2020, 1, 1, 0, 0);
        ProductVariantJpaEntity entity = new ProductVariantJpaEntity(1L, 10L, "SKU-1", "옵션",
                new BigDecimal("0"), 1, 0L, ProductVariantStatus.ACTIVE, fixed, fixed);

        java.lang.reflect.Method onCreate = ProductVariantJpaEntity.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(entity);

        assertThat(entity.getCreatedAt()).isEqualTo(fixed);
        assertThat(entity.getUpdatedAt()).isEqualTo(fixed);
    }

    @Test
    @DisplayName("onUpdate: updatedAt 갱신")
    void onUpdate_refreshesTimestamp() throws Exception {
        LocalDateTime fixed = LocalDateTime.of(2020, 1, 1, 0, 0);
        ProductVariantJpaEntity entity = new ProductVariantJpaEntity(1L, 10L, "SKU-1", "옵션",
                new BigDecimal("0"), 1, 0L, ProductVariantStatus.ACTIVE, fixed, fixed);

        java.lang.reflect.Method onUpdate = ProductVariantJpaEntity.class.getDeclaredMethod("onUpdate");
        onUpdate.setAccessible(true);
        onUpdate.invoke(entity);

        assertThat(entity.getUpdatedAt()).isAfter(fixed);
    }

    @Test
    @DisplayName("applyDomainState: 재고/상태/옵션명/추가금/수정시각 반영")
    void applyDomainState() {
        LocalDateTime now = LocalDateTime.now();
        ProductVariantJpaEntity entity = new ProductVariantJpaEntity(1L, 10L, "SKU-1", "색상:빨강",
                new BigDecimal("500"), 5, 0L, ProductVariantStatus.ACTIVE, now, now);

        LocalDateTime updated = now.plusMinutes(1);
        entity.applyDomainState(0, ProductVariantStatus.OUT_OF_STOCK, "색상:파랑",
                new BigDecimal("700"), updated);

        assertThat(entity.getStockQuantity()).isZero();
        assertThat(entity.getStatus()).isEqualTo(ProductVariantStatus.OUT_OF_STOCK);
        assertThat(entity.getOptionName()).isEqualTo("색상:파랑");
        assertThat(entity.getAdditionalPrice()).isEqualByComparingTo("700");
        assertThat(entity.getUpdatedAt()).isEqualTo(updated);
    }
}
