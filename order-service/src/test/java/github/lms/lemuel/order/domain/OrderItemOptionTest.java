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

    /**
     * 자유입력(TEXT) 축 — 각인 문구처럼 구매자가 직접 적는 옵션.
     *
     * <p>선택형과 다른 점은 하나다: <b>카탈로그에 없던 문장</b>이라 값 코드가 없다. 그래서
     * 검사할 것도 다르다 — 코드가 비었는가가 아니라, 길이가 축이 정한 상한을 넘지 않는가다.
     */
    @Nested
    @DisplayName("자유입력 스냅샷")
    class TextSnapshot {

        @Test
        @DisplayName("문구를 담고 값 코드는 비운다 — 없던 코드를 지어내지 않는다")
        void keepsTextWithoutValueCode() {
            OrderItemOption option = OrderItemOption.textSnapshot(0, "ENGRAVING", "각인 문구", "민수에게", 10);

            assertThat(option.isFreeText()).isTrue();
            assertThat(option.getTextValue()).isEqualTo("민수에게");
            assertThat(option.getValueCode()).isNull();
            assertThat(option.getValueName()).isNull();
            assertThat(option.describe()).isEqualTo("각인 문구: 민수에게");
        }

        @Test
        @DisplayName("앞뒤 공백은 저장 전에 깎는다")
        void trimsSurroundingWhitespace() {
            OrderItemOption option = OrderItemOption.textSnapshot(0, "ENGRAVING", "각인", "  민수  ", 10);

            assertThat(option.getTextValue()).isEqualTo("민수");
        }

        @Test
        @DisplayName("빈 문구는 거절한다 — 적지 않은 것과 빈 각인은 다르지 않다")
        void rejectsBlankText() {
            assertThatThrownBy(() -> OrderItemOption.textSnapshot(0, "ENGRAVING", "각인", "   ", 10))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        /*
         * 화면의 maxlength 는 요청을 직접 만들면 그냥 없는 것이다. 그래서 주문 시점에 한 번 더 센다.
         */
        @Test
        @DisplayName("축이 정한 상한을 넘으면 거절한다 — 화면 속성은 방어선이 아니다")
        void rejectsTextOverAxisLimit() {
            assertThatThrownBy(() ->
                    OrderItemOption.textSnapshot(0, "ENGRAVING", "각인", "일이삼사오육칠팔구십일", 10))
                    .isInstanceOf(OrderInvariantViolationException.class)
                    .hasMessageContaining("10자 이하");
        }

        @Test
        @DisplayName("상한 자체가 컬럼 폭(200)을 넘으면 거절한다")
        void rejectsAbsurdLimit() {
            assertThatThrownBy(() -> OrderItemOption.textSnapshot(0, "E", "각인", "가", 201))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        /*
         * 동일성이 축까지만 보는 이유 — "각인=A" 와 "각인=B" 를 다른 줄로 보면
         * 같은 차수가 두 번 들어오는 것을 막지 못한다. 문구가 달라도 각인 축은 하나다.
         */
        @Test
        @DisplayName("문구가 달라도 같은 축이면 같은 줄로 본다")
        void identityIsAxisScoped() {
            OrderItemOption a = OrderItemOption.textSnapshot(0, "ENGRAVING", "각인", "민수", 10);
            OrderItemOption b = OrderItemOption.textSnapshot(0, "ENGRAVING", "각인", "영희", 10);

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("선택형과 자유입력은 서로 다른 줄이다")
        void freeTextDiffersFromSelected() {
            OrderItemOption text = OrderItemOption.textSnapshot(0, "AXIS", "축", "문구", 10);
            OrderItemOption selected = OrderItemOption.snapshot(0, "AXIS", "축", "RED", "빨강");

            assertThat(text).isNotEqualTo(selected);
        }
    }
}
