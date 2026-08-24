package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProductOptionAxis / ProductOptionValue — 상품이 채택한 축과 값")
class ProductOptionAxisTest {

    private static final Long PRODUCT_ID = 100L;
    private static final Long AXIS_ID = 1L;

    @Nested
    @DisplayName("상품 옵션 축")
    class Axis {

        @Test
        @DisplayName("차수 0 은 1차 옵션이다")
        void firstAxis() {
            ProductOptionAxis axis = ProductOptionAxis.create(PRODUCT_ID, AXIS_ID, 0, true);

            assertThat(axis.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(axis.getAxisId()).isEqualTo(AXIS_ID);
            assertThat(axis.isFirstAxis()).isTrue();
            assertThat(axis.isRequired()).isTrue();
        }

        @Test
        @DisplayName("차수에 상한이 없다 — 3차 이상도 만들 수 있다")
        void allowsDeepAxes() {
            assertThat(ProductOptionAxis.create(PRODUCT_ID, AXIS_ID, 7, true).getSortOrder())
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("음수 차수는 거부한다 (경계)")
        void rejectsNegativeSortOrder() {
            assertThatThrownBy(() -> ProductOptionAxis.create(PRODUCT_ID, AXIS_ID, -1, true))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("0 이상");
        }

        @Test
        @DisplayName("productId·axisId null 은 거부한다")
        void rejectsNullReferences() {
            assertThatThrownBy(() -> ProductOptionAxis.create(null, AXIS_ID, 0, true))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ProductOptionAxis.create(PRODUCT_ID, null, 0, true))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("차수를 바꾸면 1차 여부도 따라 바뀐다")
        void changeSortOrder() {
            ProductOptionAxis axis = ProductOptionAxis.create(PRODUCT_ID, AXIS_ID, 0, true);

            axis.changeSortOrder(1);

            assertThat(axis.getSortOrder()).isEqualTo(1);
            assertThat(axis.isFirstAxis()).isFalse();
        }

        @Test
        @DisplayName("음수 차수로는 바꿀 수 없다")
        void rejectsNegativeSortOrderChange() {
            ProductOptionAxis axis = ProductOptionAxis.create(PRODUCT_ID, AXIS_ID, 2, true);

            assertThatThrownBy(() -> axis.changeSortOrder(-1))
                    .isInstanceOf(ProductInvariantViolationException.class);
            assertThat(axis.getSortOrder()).isEqualTo(2);
        }

        @Test
        @DisplayName("필수·선택을 전환한다")
        void togglesRequired() {
            ProductOptionAxis axis = ProductOptionAxis.create(PRODUCT_ID, AXIS_ID, 0, true);

            axis.markOptional();
            assertThat(axis.isRequired()).isFalse();

            axis.markRequired();
            assertThat(axis.isRequired()).isTrue();
        }

        @Test
        @DisplayName("rehydrate 로 저장 상태를 복원하고 id 는 1 회만 부여한다")
        void identity() {
            ProductOptionAxis restored = ProductOptionAxis.rehydrate(5L, PRODUCT_ID, AXIS_ID, 1, false);
            assertThat(restored.getId()).isEqualTo(5L);
            assertThat(restored.isRequired()).isFalse();

            ProductOptionAxis created = ProductOptionAxis.create(PRODUCT_ID, AXIS_ID, 0, true);
            created.assignId(9L);
            assertThatThrownBy(() -> created.assignId(10L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("상품 옵션 값")
    class Value {

        private static final Long PRODUCT_OPTION_AXIS_ID = 50L;
        private static final Long AXIS_VALUE_ID = 20L;

        @Test
        @DisplayName("활성 상태로 만들어진다")
        void createsActive() {
            ProductOptionValue value =
                    ProductOptionValue.create(PRODUCT_OPTION_AXIS_ID, AXIS_VALUE_ID, 0);

            assertThat(value.getProductOptionAxisId()).isEqualTo(PRODUCT_OPTION_AXIS_ID);
            assertThat(value.getAxisValueId()).isEqualTo(AXIS_VALUE_ID);
            assertThat(value.isActive()).isTrue();
        }

        @Test
        @DisplayName("음수 정렬 순서는 거부한다 (경계)")
        void rejectsNegativeSortOrder() {
            assertThatThrownBy(() ->
                    ProductOptionValue.create(PRODUCT_OPTION_AXIS_ID, AXIS_VALUE_ID, -1))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("참조 null 은 거부한다")
        void rejectsNullReferences() {
            assertThatThrownBy(() -> ProductOptionValue.create(null, AXIS_VALUE_ID, 0))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ProductOptionValue.create(PRODUCT_OPTION_AXIS_ID, null, 0))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("비활성화는 값 삭제가 아니라 신규 선택 차단이다")
        void deactivateKeepsIdentity() {
            ProductOptionValue value =
                    ProductOptionValue.create(PRODUCT_OPTION_AXIS_ID, AXIS_VALUE_ID, 0);

            value.deactivate();

            assertThat(value.isActive()).isFalse();
            assertThat(value.getAxisValueId()).isEqualTo(AXIS_VALUE_ID);

            value.activate();
            assertThat(value.isActive()).isTrue();
        }

        @Test
        @DisplayName("정렬 순서를 바꾸고 음수는 거부한다")
        void changeSortOrder() {
            ProductOptionValue value =
                    ProductOptionValue.create(PRODUCT_OPTION_AXIS_ID, AXIS_VALUE_ID, 0);

            value.changeSortOrder(3);
            assertThat(value.getSortOrder()).isEqualTo(3);

            assertThatThrownBy(() -> value.changeSortOrder(-1))
                    .isInstanceOf(ProductInvariantViolationException.class);
        }

        @Test
        @DisplayName("소속 축을 판정한다")
        void belongsTo() {
            ProductOptionValue value =
                    ProductOptionValue.create(PRODUCT_OPTION_AXIS_ID, AXIS_VALUE_ID, 0);
            ProductOptionAxis owner =
                    ProductOptionAxis.rehydrate(PRODUCT_OPTION_AXIS_ID, PRODUCT_ID, AXIS_ID, 0, true);
            ProductOptionAxis other =
                    ProductOptionAxis.rehydrate(999L, PRODUCT_ID, AXIS_ID, 1, true);

            assertThat(value.belongsTo(owner)).isTrue();
            assertThat(value.belongsTo(other)).isFalse();
            assertThatThrownBy(() -> value.belongsTo(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rehydrate 로 복원하고 id 는 1 회만 부여한다")
        void identity() {
            ProductOptionValue restored =
                    ProductOptionValue.rehydrate(4L, PRODUCT_OPTION_AXIS_ID, AXIS_VALUE_ID, 2, false);
            assertThat(restored.getId()).isEqualTo(4L);
            assertThat(restored.isActive()).isFalse();

            ProductOptionValue created =
                    ProductOptionValue.create(PRODUCT_OPTION_AXIS_ID, AXIS_VALUE_ID, 0);
            created.assignId(8L);
            assertThatThrownBy(() -> created.assignId(9L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
