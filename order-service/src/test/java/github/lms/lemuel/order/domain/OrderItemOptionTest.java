package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OrderItemOption — 주문 라인의 옵션 스냅샷")
class OrderItemOptionTest {

    @Nested
    @DisplayName("스냅샷 생성")
    class Snapshot {

        @Test
        @DisplayName("축·값의 코드와 이름을 그대로 담는다")
        void keepsCodesAndNames() {
            OrderItemOption option = OrderItemOption.snapshot(0, "색상", "색상", "빨강", "빨강");

            assertThat(option.getAxisSortOrder()).isZero();
            assertThat(option.getAxisCode()).isEqualTo("색상");
            assertThat(option.getValueName()).isEqualTo("빨강");
            assertThat(option.getId()).isNull();
            assertThat(option.getOrderItemId()).isNull();
        }

        @Test
        @DisplayName("표시 문자열을 만든다")
        void describes() {
            assertThat(OrderItemOption.snapshot(0, "COLOR", "색상", "RED", "빨강").describe())
                    .isEqualTo("색상: 빨강");
        }

        @Test
        @DisplayName("음수 차수는 거부한다 (경계)")
        void rejectsNegativeSortOrder() {
            assertThatThrownBy(() -> OrderItemOption.snapshot(-1, "색상", "색상", "빨강", "빨강"))
                    .isInstanceOf(OrderInvariantViolationException.class)
                    .hasMessageContaining("0 이상");
        }

        @Test
        @DisplayName("빈 코드·이름은 거부한다")
        void rejectsBlanks() {
            assertThatThrownBy(() -> OrderItemOption.snapshot(0, " ", "색상", "빨강", "빨강"))
                    .isInstanceOf(OrderInvariantViolationException.class);
            assertThatThrownBy(() -> OrderItemOption.snapshot(0, "색상", null, "빨강", "빨강"))
                    .isInstanceOf(OrderInvariantViolationException.class);
            assertThatThrownBy(() -> OrderItemOption.snapshot(0, "색상", "색상", "", "빨강"))
                    .isInstanceOf(OrderInvariantViolationException.class);
            assertThatThrownBy(() -> OrderItemOption.snapshot(0, "색상", "색상", "빨강", null))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        @Test
        @DisplayName("코드 50 자·이름 100 자는 허용, 초과는 거부한다 (경계)")
        void enforcesLengthBoundaries() {
            assertThat(OrderItemOption.snapshot(0, "가".repeat(50), "가".repeat(100),
                    "나".repeat(50), "나".repeat(100)).getAxisCode()).hasSize(50);

            assertThatThrownBy(() -> OrderItemOption.snapshot(0, "가".repeat(51), "축", "값", "값"))
                    .isInstanceOf(OrderInvariantViolationException.class);
            assertThatThrownBy(() -> OrderItemOption.snapshot(0, "축", "가".repeat(101), "값", "값"))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        @Test
        @DisplayName("앞뒤 공백은 제거한다")
        void trims() {
            assertThat(OrderItemOption.snapshot(0, " 색상 ", " 색상 ", " 빨강 ", " 빨강 ").getAxisCode())
                    .isEqualTo("색상");
        }
    }

    @Nested
    @DisplayName("동일성")
    class Equality {

        @Test
        @DisplayName("차수·축 코드·값 코드가 같으면 같은 옵션이다")
        void equalsByCodes() {
            assertThat(OrderItemOption.snapshot(0, "COLOR", "색상", "RED", "빨강"))
                    .isEqualTo(OrderItemOption.snapshot(0, "COLOR", "컬러", "RED", "레드"))
                    .hasSameHashCodeAs(OrderItemOption.snapshot(0, "COLOR", "색상", "RED", "빨강"));
        }

        @Test
        @DisplayName("값 코드가 다르면 다른 옵션이다")
        void differsByValueCode() {
            assertThat(OrderItemOption.snapshot(0, "COLOR", "색상", "RED", "빨강"))
                    .isNotEqualTo(OrderItemOption.snapshot(0, "COLOR", "색상", "BLUE", "파랑"));
        }
    }

    @Nested
    @DisplayName("주문 라인 부착")
    class Attachment {

        @Test
        @DisplayName("라인에 id 가 부여되면 옵션도 함께 붙는다")
        void attachesOnAssignId() {
            OrderItem item = OrderItem.newItem(1L, 2L, "SKU-1", "상품",
                    new BigDecimal("1000"), 1,
                    List.of(OrderItemOption.snapshot(0, "색상", "색상", "빨강", "빨강")));

            item.assignId(77L);

            assertThat(item.getOptions()).singleElement()
                    .extracting(OrderItemOption::getOrderItemId).isEqualTo(77L);
        }

        @Test
        @DisplayName("같은 차수가 두 번 들어오면 라인 생성을 거부한다")
        void rejectsDuplicateDepth() {
            List<OrderItemOption> duplicated = List.of(
                    OrderItemOption.snapshot(0, "색상", "색상", "빨강", "빨강"),
                    OrderItemOption.snapshot(0, "사이즈", "사이즈", "L", "L"));

            assertThatThrownBy(() -> OrderItem.newItem(1L, 2L, "SKU-1", "상품",
                    new BigDecimal("1000"), 1, duplicated))
                    .isInstanceOf(OrderInvariantViolationException.class)
                    .hasMessageContaining("두 번");
        }

        @Test
        @DisplayName("옵션은 차수 순으로 정렬돼 나오고 표시 문자열로 합쳐진다")
        void sortsAndDescribes() {
            OrderItem item = OrderItem.newItem(1L, 2L, "SKU-1", "상품",
                    new BigDecimal("1000"), 1, List.of(
                            OrderItemOption.snapshot(1, "사이즈", "사이즈", "L", "L"),
                            OrderItemOption.snapshot(0, "색상", "색상", "빨강", "빨강")));

            assertThat(item.describeOptions()).isEqualTo("색상: 빨강 / 사이즈: L");
        }

        @Test
        @DisplayName("옵션 없는 라인은 빈 목록·빈 문자열이다")
        void emptyWhenNoOptions() {
            OrderItem item = OrderItem.newItem(1L, null, null, "상품", new BigDecimal("1000"), 1);

            assertThat(item.getOptions()).isEmpty();
            assertThat(item.describeOptions()).isEmpty();
        }

        @Test
        @DisplayName("반환 목록은 불변이다 — 주문서를 밖에서 고칠 수 없다")
        void optionsAreImmutable() {
            OrderItem item = OrderItem.newItem(1L, 2L, "SKU-1", "상품",
                    new BigDecimal("1000"), 1,
                    List.of(OrderItemOption.snapshot(0, "색상", "색상", "빨강", "빨강")));

            assertThatThrownBy(() -> item.getOptions()
                    .add(OrderItemOption.snapshot(1, "사이즈", "사이즈", "L", "L")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
