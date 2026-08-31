package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.util.Objects;

/**
 * 주문 라인의 옵션 선택 스냅샷 한 줄 — "이 주문에서 색상은 빨강이었다".
 *
 * <p>옵션 축·값이 카탈로그 테이블로 쪼개진 뒤, 주문서를 복원하려면 조인을 네 번 타야 하고 값이
 * 비활성화되거나 이름이 바뀌면 복원이 흐려진다. 주문서는 몇 년 뒤에도 그때 그대로 읽혀야 하므로
 * 축·값의 <b>코드와 이름을 주문 시점 그대로</b> 적어 둔다.
 *
 * <p><b>금액은 담지 않는다.</b> 라인 단가는 {@link OrderItem#getUnitPrice()} 가 이미 보존한다.
 * 축별 가산금을 여기 또 적으면 합계가 두 곳에서 갈리고, 환불 역산이 어느 쪽을 믿어야 할지 모호해진다.
 */
public final class OrderItemOption {

    private static final int MAX_CODE_LENGTH = 50;
    private static final int MAX_NAME_LENGTH = 100;
    /** 자유입력의 절대 상한 — 컬럼 폭과 같다. 축별 상한은 이보다 짧을 수 있다. */
    public static final int MAX_TEXT_LENGTH = 200;

    private Long id;
    private Long orderItemId;
    private final int axisSortOrder;
    private final String axisCode;
    private final String axisName;
    private final String valueCode;
    private final String valueName;
    private final String textValue;

    private OrderItemOption(Long id, Long orderItemId, int axisSortOrder, String axisCode,
                            String axisName, String valueCode, String valueName, String textValue) {
        this.id = id;
        this.orderItemId = orderItemId;
        this.axisSortOrder = axisSortOrder;
        this.axisCode = axisCode;
        this.axisName = axisName;
        this.valueCode = valueCode;
        this.valueName = valueName;
        this.textValue = textValue;
    }

    public static OrderItemOption snapshot(int axisSortOrder, String axisCode, String axisName,
                                           String valueCode, String valueName) {
        requireDepth(axisSortOrder);
        return new OrderItemOption(null, null, axisSortOrder,
                requireCode(axisCode, "축 코드"), requireName(axisName, "축 이름"),
                requireCode(valueCode, "값 코드"), requireName(valueName, "값 이름"), null);
    }

    /**
     * 자유입력 축(TEXT)의 스냅샷 — "각인: 민수에게".
     *
     * <p>선택형과 달리 값 코드가 없다. 카탈로그에 없던 문장이므로 코드를 지어낼 수 없고,
     * 지어내면 그 코드로 집계하거나 값 목록과 대조할 수 있는 것처럼 보여 더 나쁘다.
     *
     * <p>{@code maxLength} 는 축이 정한 상한이다. 상한 검사를 주문 시점에 한 번 더 하는 이유는,
     * 화면의 maxlength 속성은 요청을 직접 만들면 그냥 없는 것이기 때문이다.
     */
    public static OrderItemOption textSnapshot(int axisSortOrder, String axisCode, String axisName,
                                                String text, int maxLength) {
        requireDepth(axisSortOrder);
        if (maxLength < 1 || maxLength > MAX_TEXT_LENGTH) {
            throw new OrderInvariantViolationException(
                    "자유입력 상한은 1~" + MAX_TEXT_LENGTH + "자: " + maxLength);
        }
        if (text == null || text.isBlank()) {
            throw new OrderInvariantViolationException("자유입력 값은 필수");
        }
        String trimmed = text.trim();
        if (trimmed.length() > maxLength) {
            throw new OrderInvariantViolationException(
                    axisName + " 은 " + maxLength + "자 이하 (" + trimmed.length() + "자 들어옴)");
        }
        return new OrderItemOption(null, null, axisSortOrder,
                requireCode(axisCode, "축 코드"), requireName(axisName, "축 이름"),
                null, null, trimmed);
    }

    public static OrderItemOption rehydrate(Long id, Long orderItemId, int axisSortOrder,
                                            String axisCode, String axisName,
                                            String valueCode, String valueName) {
        return rehydrate(id, orderItemId, axisSortOrder, axisCode, axisName,
                valueCode, valueName, null);
    }

    public static OrderItemOption rehydrate(Long id, Long orderItemId, int axisSortOrder,
                                            String axisCode, String axisName,
                                            String valueCode, String valueName, String textValue) {
        return new OrderItemOption(id, orderItemId, axisSortOrder, axisCode, axisName,
                valueCode, valueName, textValue);
    }

    private static void requireDepth(int axisSortOrder) {
        if (axisSortOrder < 0) {
            throw new OrderInvariantViolationException("옵션 차수는 0 이상");
        }
    }

    private static String requireCode(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new OrderInvariantViolationException(what + " 은 필수");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_CODE_LENGTH) {
            throw new OrderInvariantViolationException(what + " 은 " + MAX_CODE_LENGTH + "자 이하");
        }
        return trimmed;
    }

    private static String requireName(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new OrderInvariantViolationException(what + " 은 필수");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new OrderInvariantViolationException(what + " 은 " + MAX_NAME_LENGTH + "자 이하");
        }
        return trimmed;
    }

    void attachToItem(Long orderItemId) {
        if (this.orderItemId != null && !this.orderItemId.equals(orderItemId)) {
            throw new IllegalStateException("이미 다른 주문 라인에 속한 옵션: " + this.orderItemId);
        }
        this.orderItemId = orderItemId;
    }

    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여 가능");
        }
        this.id = id;
    }

    /** 자유입력 축인가 — 값 코드 없이 문구만 남은 줄. */
    public boolean isFreeText() {
        return textValue != null;
    }

    /** 주문서 표시용 한 줄 — "색상: 빨강", "각인: 민수에게". */
    public String describe() {
        return axisName + ": " + (isFreeText() ? textValue : valueName);
    }

    public Long getId() { return id; }
    public Long getOrderItemId() { return orderItemId; }
    public int getAxisSortOrder() { return axisSortOrder; }
    public String getAxisCode() { return axisCode; }
    public String getAxisName() { return axisName; }
    public String getValueCode() { return valueCode; }
    public String getValueName() { return valueName; }
    public String getTextValue() { return textValue; }

    /**
     * 동일성은 <b>축 하나에 값 하나</b> 라는 불변식을 지키기 위한 것이다 — 그래서 축까지만 본다.
     *
     * <p>자유입력을 동일성에 넣으면 "각인=A" 와 "각인=B" 가 서로 다른 줄이 되어 같은 차수가
     * 두 번 들어오는 것을 막지 못한다. 문구가 달라도 각인 축은 여전히 하나다.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderItemOption other)) return false;
        return axisSortOrder == other.axisSortOrder
                && axisCode.equals(other.axisCode)
                && Objects.equals(valueCode, other.valueCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(axisSortOrder, axisCode, valueCode);
    }
}
