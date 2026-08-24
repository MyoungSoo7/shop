package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.ProductImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ProductImageMapperTest {

    private final ProductImageMapper mapper = new ProductImageMapper();

    @Test
    @DisplayName("toJpaEntity: null 입력이면 null 반환")
    void toJpaEntity_null() {
        assertThat(mapper.toJpaEntity(null)).isNull();
    }

    @Test
    @DisplayName("toDomainEntity: null 입력이면 null 반환")
    void toDomainEntity_null() {
        assertThat(mapper.toDomainEntity(null)).isNull();
    }

    @Test
    @DisplayName("도메인 -> 엔티티 -> 도메인 왕복 시 전 필드 보존")
    void roundTrip() {
        LocalDateTime now = LocalDateTime.now();
        ProductImage image = new ProductImage(1L, 10L, "orig.jpg", "stored.jpg", "/path/stored.jpg",
                "/url/stored.jpg", "image/jpeg", 2048L, 800, 600, "checksum123", true, 2,
                now, now, null);

        ProductImageJpaEntity entity = mapper.toJpaEntity(image);
        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getProductId()).isEqualTo(10L);
        assertThat(entity.getOriginalFileName()).isEqualTo("orig.jpg");
        assertThat(entity.getStoredFileName()).isEqualTo("stored.jpg");
        assertThat(entity.getFilePath()).isEqualTo("/path/stored.jpg");
        assertThat(entity.getUrl()).isEqualTo("/url/stored.jpg");
        assertThat(entity.getContentType()).isEqualTo("image/jpeg");
        assertThat(entity.getSizeBytes()).isEqualTo(2048L);
        assertThat(entity.getWidth()).isEqualTo(800);
        assertThat(entity.getHeight()).isEqualTo(600);
        assertThat(entity.getChecksum()).isEqualTo("checksum123");
        assertThat(entity.getIsPrimary()).isTrue();
        assertThat(entity.getOrderIndex()).isEqualTo(2);
        assertThat(entity.getDeletedAt()).isNull();

        ProductImage restored = mapper.toDomainEntity(entity);
        assertThat(restored.getId()).isEqualTo(1L);
        assertThat(restored.getProductId()).isEqualTo(10L);
        assertThat(restored.getOriginalFileName()).isEqualTo("orig.jpg");
        assertThat(restored.getStoredFileName()).isEqualTo("stored.jpg");
        assertThat(restored.getFilePath()).isEqualTo("/path/stored.jpg");
        assertThat(restored.getUrl()).isEqualTo("/url/stored.jpg");
        assertThat(restored.getContentType()).isEqualTo("image/jpeg");
        assertThat(restored.getSizeBytes()).isEqualTo(2048L);
        assertThat(restored.getWidth()).isEqualTo(800);
        assertThat(restored.getHeight()).isEqualTo(600);
        assertThat(restored.getChecksum()).isEqualTo("checksum123");
        assertThat(restored.getIsPrimary()).isTrue();
        assertThat(restored.getOrderIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("deletedAt 이 있는 경우도 보존된다")
    void roundTrip_withDeletedAt() {
        LocalDateTime now = LocalDateTime.now();
        ProductImage image = new ProductImage(2L, 10L, "orig2.jpg", "stored2.jpg", "/path2",
                "/url2", "image/png", 512L, null, null, null, false, 0,
                now, now, now);

        ProductImageJpaEntity entity = mapper.toJpaEntity(image);
        assertThat(entity.getDeletedAt()).isEqualTo(now);

        ProductImage restored = mapper.toDomainEntity(entity);
        assertThat(restored.getDeletedAt()).isEqualTo(now);
        assertThat(restored.isDeleted()).isTrue();
    }
}
