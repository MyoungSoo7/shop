package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.InvalidProductStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProductVariant — 조합 서명 부여")
class ProductVariantSignatureTest {

    private static ProductVariant variant() {
        return ProductVariant.create(1L, "SKU-1", "색상:빨강", BigDecimal.ZERO, 10);
    }

    @Test
    @DisplayName("새 SKU 는 서명이 없다")
    void newVariantHasNoSignature() {
        ProductVariant variant = variant();

        assertThat(variant.getOptionSignature()).isNull();
        assertThat(variant.hasOptionSignature()).isFalse();
    }

    @Test
    @DisplayName("서명을 부여하면 보유 상태가 된다")
    void assignsSignature() {
        ProductVariant variant = variant();

        variant.assignOptionSignature("a".repeat(64));

        assertThat(variant.getOptionSignature()).isEqualTo("a".repeat(64));
        assertThat(variant.hasOptionSignature()).isTrue();
    }

    @Test
    @DisplayName("같은 서명 재부여는 no-op — 백필을 몇 번 돌려도 안전하다")
    void reassignSameSignatureIsIdempotent() {
        ProductVariant variant = variant();
        String signature = "b".repeat(64);

        variant.assignOptionSignature(signature);
        variant.assignOptionSignature(signature);

        assertThat(variant.getOptionSignature()).isEqualTo(signature);
    }

    @Test
    @DisplayName("다른 서명 덮어쓰기는 거부한다 — 조합이 바뀌면 그건 다른 SKU 다")
    void rejectsDifferentSignature() {
        ProductVariant variant = variant();
        variant.assignOptionSignature("c".repeat(64));

        assertThatThrownBy(() -> variant.assignOptionSignature("d".repeat(64)))
                .isInstanceOf(InvalidProductStateException.class)
                .hasMessageContaining("SKU-1");

        assertThat(variant.getOptionSignature()).isEqualTo("c".repeat(64));
    }

    @Test
    @DisplayName("null 서명은 거부한다")
    void rejectsNullSignature() {
        assertThatThrownBy(() -> variant().assignOptionSignature(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rehydrate 로 저장된 서명을 복원한다")
    void rehydratesSignature() {
        ProductVariant restored = ProductVariant.rehydrate(
                5L, 1L, "SKU-1", "색상:빨강", BigDecimal.ZERO, null, null, 10, 0L,
                ProductVariantStatus.ACTIVE, "e".repeat(64),
                LocalDateTime.now(), LocalDateTime.now());

        assertThat(restored.getOptionSignature()).isEqualTo("e".repeat(64));
    }

    @Test
    @DisplayName("서명 없는 기존 rehydrate 오버로드는 null 서명으로 복원한다")
    void legacyRehydrateHasNoSignature() {
        ProductVariant restored = ProductVariant.rehydrate(
                5L, 1L, "SKU-1", "색상:빨강", BigDecimal.ZERO, 10, 0L,
                ProductVariantStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());

        assertThat(restored.hasOptionSignature()).isFalse();
    }
}
