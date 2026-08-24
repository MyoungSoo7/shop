package github.lms.lemuel.product.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProductImage — 전체 생성자/접근자/상태 전이 커버리지")
class ProductImageFullTest {

    @Test
    @DisplayName("전체 인자 생성자 — 모든 값 보존")
    void fullConstructor_allValues() {
        LocalDateTime now = LocalDateTime.now();
        ProductImage i = new ProductImage(9L, 3L, "orig.png", "stored.png", "/p/stored.png",
                "/u/stored.png", "image/png", 2048L, 640, 480, "abc123", true, 2, now, now, null);

        assertThat(i.getId()).isEqualTo(9L);
        assertThat(i.getProductId()).isEqualTo(3L);
        assertThat(i.getOriginalFileName()).isEqualTo("orig.png");
        assertThat(i.getStoredFileName()).isEqualTo("stored.png");
        assertThat(i.getFilePath()).isEqualTo("/p/stored.png");
        assertThat(i.getUrl()).isEqualTo("/u/stored.png");
        assertThat(i.getContentType()).isEqualTo("image/png");
        assertThat(i.getSizeBytes()).isEqualTo(2048L);
        assertThat(i.getWidth()).isEqualTo(640);
        assertThat(i.getHeight()).isEqualTo(480);
        assertThat(i.getChecksum()).isEqualTo("abc123");
        assertThat(i.getIsPrimary()).isTrue();
        assertThat(i.getOrderIndex()).isEqualTo(2);
        assertThat(i.getCreatedAt()).isEqualTo(now);
        assertThat(i.getUpdatedAt()).isEqualTo(now);
        assertThat(i.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("전체 인자 생성자 — null 방어 기본값(isPrimary/orderIndex/createdAt/updatedAt)")
    void fullConstructor_nullDefaults() {
        ProductImage i = new ProductImage(null, null, null, null, null, null, "image/webp",
                1L, null, null, null, null, null, null, null, null);
        assertThat(i.getIsPrimary()).isFalse();
        assertThat(i.getOrderIndex()).isZero();
        assertThat(i.getCreatedAt()).isNotNull();
        assertThat(i.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("모든 세터 왕복")
    void settersRoundTrip() {
        LocalDateTime t = LocalDateTime.now();
        ProductImage i = new ProductImage(1L, 2L, "o", "s", "/f", "/u", "image/jpeg",
                10L, 5, 6, "c", true, 4, t, t, t);

        assertThat(i.getId()).isEqualTo(1L);
        assertThat(i.getProductId()).isEqualTo(2L);
        assertThat(i.getOriginalFileName()).isEqualTo("o");
        assertThat(i.getStoredFileName()).isEqualTo("s");
        assertThat(i.getFilePath()).isEqualTo("/f");
        assertThat(i.getUrl()).isEqualTo("/u");
        assertThat(i.getContentType()).isEqualTo("image/jpeg");
        assertThat(i.getSizeBytes()).isEqualTo(10L);
        assertThat(i.getWidth()).isEqualTo(5);
        assertThat(i.getHeight()).isEqualTo(6);
        assertThat(i.getChecksum()).isEqualTo("c");
        assertThat(i.getIsPrimary()).isTrue();
        assertThat(i.getOrderIndex()).isEqualTo(4);
        assertThat(i.getCreatedAt()).isEqualTo(t);
        assertThat(i.getUpdatedAt()).isEqualTo(t);
        assertThat(i.getDeletedAt()).isEqualTo(t);
        assertThat(i.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("changeOrder — 유효한 값 반영")
    void changeOrder_valid() {
        ProductImage i = ProductImage.create(1L, "a.jpg", "s", "/p", "/u", "image/jpeg", 1024L, 10, 10, 0);
        i.changeOrder(7);
        assertThat(i.getOrderIndex()).isEqualTo(7);
    }
}
