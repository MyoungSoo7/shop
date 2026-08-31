package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 매입가와 마진.
 *
 * <p>여기서 지키려는 건 숫자 하나가 아니라 <b>"모른다"와 "0"을 섞지 않는다</b>는 규칙이다.
 * 매입가 미입력 SKU 를 0원 매입으로 취급하면 마진 100% 짜리 상품이 무더기로 생기고,
 * 리포트는 성공적으로 거짓말을 한다 — 실패도 나지 않아 아무도 눈치채지 못한다.
 */
@DisplayName("ProductVariant — 매입가·마진")
class ProductVariantMarginTest {

    private static ProductVariant variant(BigDecimal additionalPrice, BigDecimal discountPrice,
                                           BigDecimal discountRate, BigDecimal purchasePrice) {
        return ProductVariant.rehydrate(1L, 10L, "SKU-1", "색상:빨강",
                additionalPrice, discountPrice, discountRate, purchasePrice, 10, 0L,
                ProductVariantStatus.ACTIVE, null, LocalDateTime.now(), LocalDateTime.now());
    }

    @Nested
    @DisplayName("매입가 설정")
    class ChangePurchasePrice {

        @Test
        @DisplayName("음수 매입가는 거부한다 — 역마진이 아니라 입력 사고다")
        void rejectsNegative() {
            ProductVariant v = variant(BigDecimal.ZERO, null, null, null);

            assertThatThrownBy(() -> v.changePurchasePrice(new BigDecimal("-1")))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("0 이상");
        }

        @Test
        @DisplayName("null 을 넣으면 '모른다'로 되돌아간다 — 0 으로 덮는 것과 다르다")
        void nullClearsInsteadOfZeroing() {
            ProductVariant v = variant(BigDecimal.ZERO, null, null, new BigDecimal("3000"));

            v.changePurchasePrice(null);

            assertThat(v.hasPurchasePrice()).isFalse();
            assertThat(v.getPurchasePrice()).isNull();
            assertThat(v.marginAmount(new BigDecimal("10000"))).isNull();
        }

        @Test
        @DisplayName("0 은 '0원에 샀다'로 그대로 받는다 — 마진은 판매가 전액")
        void zeroIsAValueNotAnAbsence() {
            ProductVariant v = variant(BigDecimal.ZERO, null, null, null);

            v.changePurchasePrice(BigDecimal.ZERO);

            assertThat(v.hasPurchasePrice()).isTrue();
            assertThat(v.marginAmount(new BigDecimal("10000")))
                    .isEqualByComparingTo("10000");
            assertThat(v.marginRate(new BigDecimal("10000")))
                    .isEqualByComparingTo("100.00");
        }
    }

    @Nested
    @DisplayName("마진 계산")
    class Margin {

        @Test
        @DisplayName("매입가를 모르면 마진액·마진율 모두 null — 0 이 아니다")
        void unknownCostYieldsNull() {
            ProductVariant v = variant(BigDecimal.ZERO, null, null, null);

            assertThat(v.marginAmount(new BigDecimal("10000"))).isNull();
            assertThat(v.marginRate(new BigDecimal("10000"))).isNull();
        }

        @Test
        @DisplayName("판매가는 기준가+추가금-할인으로 그때그때 계산한 값을 쓴다")
        void usesEffectiveUnitPriceNotBasePrice() {
            // 기준가 10000 + 추가금 2000 = 12000, 정액 1000 → 11000, 정률 10% → 9900
            ProductVariant v = variant(new BigDecimal("2000"), new BigDecimal("1000"),
                    new BigDecimal("10"), new BigDecimal("6000"));

            assertThat(v.effectiveUnitPrice(new BigDecimal("10000")))
                    .isEqualByComparingTo("9900");
            assertThat(v.marginAmount(new BigDecimal("10000")))
                    .isEqualByComparingTo("3900");
            // 3900 / 9900 = 39.3939... → 소수 둘째 자리 반올림
            assertThat(v.marginRate(new BigDecimal("10000")))
                    .isEqualByComparingTo("39.39");
        }

        @Test
        @DisplayName("역마진은 음수 그대로 드러낸다 — 0 으로 깎으면 손해 보는 SKU 가 안 보인다")
        void negativeMarginIsNotClamped() {
            ProductVariant v = variant(BigDecimal.ZERO, null, null, new BigDecimal("12000"));

            assertThat(v.marginAmount(new BigDecimal("10000")))
                    .isEqualByComparingTo("-2000");
            assertThat(v.marginRate(new BigDecimal("10000")))
                    .isEqualByComparingTo("-20.00");
        }

        @Test
        @DisplayName("판매가가 0 이면 마진율은 null — 0 으로 나눌 수 없고, 마진액은 그대로 낸다")
        void zeroSellingPriceHasNoRate() {
            // 기준가 10000 - 정액 10000 = 0
            ProductVariant v = variant(BigDecimal.ZERO, new BigDecimal("10000"), null,
                    new BigDecimal("3000"));

            assertThat(v.effectiveUnitPrice(new BigDecimal("10000"))).isEqualByComparingTo("0");
            assertThat(v.marginAmount(new BigDecimal("10000"))).isEqualByComparingTo("-3000");
            assertThat(v.marginRate(new BigDecimal("10000"))).isNull();
        }

        @Test
        @DisplayName("마진율의 분모는 판매가다 — 매입가 대비 가산율(markup)이 아니다")
        void rateIsGrossMarginNotMarkup() {
            ProductVariant v = variant(BigDecimal.ZERO, null, null, new BigDecimal("8000"));

            // 매출총이익률 (10000-8000)/10000 = 20%. markup 이면 2000/8000 = 25% 였을 것이다.
            assertThat(v.marginRate(new BigDecimal("10000")))
                    .isEqualByComparingTo("20.00");
        }
    }

    @Test
    @DisplayName("매입가 없는 옛 rehydrate 호출은 '모른다'로 살아난다")
    void legacyRehydrateKeepsCostUnknown() {
        ProductVariant v = ProductVariant.rehydrate(1L, 10L, "SKU-1", "색상:빨강",
                BigDecimal.ZERO, null, null, 10, 0L,
                ProductVariantStatus.ACTIVE, null, LocalDateTime.now(), LocalDateTime.now());

        assertThat(v.hasPurchasePrice()).isFalse();
    }
}
