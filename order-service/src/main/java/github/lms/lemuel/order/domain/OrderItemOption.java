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

    private Long id;
    private Long orderItemId;
    private final int axisSortOrder;
    private final String axisCode;
    private final String axisName;
    private final String valueCode;
    private final String valueName;

    private OrderItemOption(Long id, Long orderItemId, int axisSortOrder, String axisCode,
                            String axisName, String valueCode, String valueName) {
        this.id = id;
        this.orderItemId = orderItemId;
        this.axisSortOrder = axisSortOrder;
        this.axisCode = axisCode;
        this.axisName = axisName;
        this.valueCode = valueCode;
        this.valueName = valueName;
    }

    public static OrderItemOption snapshot(int axisSortOrder, String axisCode, String axisName,
                                           String valueCode, String valueName) {
        if (axisSortOrder < 0) {
            throw new OrderInvariantViolationException("옵션 차수는 0 이상");
        }
        return new OrderItemOption(null, null, axisSortOrder,
                requireCode(axisCode, "축 코드"), requireName(axisName, "축 이름"),
                requireCode(valueCode, "값 코드"), requireName(valueName, "값 이름"));
    }

    public static OrderItemOption rehydrate(Long id, Long orderItemId, int axisSortOrder,
                                            String axisCode, String axisName,
                                            String valueCode, String valueName) {
        return new OrderItemOption(id, orderItemId, axisSortOrder, axisCode, axisName,
                valueCode, valueName);
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

    /** 주문서 표시용 한 줄 — "색상: 빨강". */
    public String describe() {
        return axisName + ": " + valueName;
    }

    public Long getId() { return id; }
    public Long getOrderItemId() { return orderItemId; }
    public int getAxisSortOrder() { return axisSortOrder; }
    public String getAxisCode() { return axisCode; }
    public String getAxisName() { return axisName; }
    public String getValueCode() { return valueCode; }
    public String getValueName() { return valueName; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderItemOption other)) return false;
        return axisSortOrder == other.axisSortOrder
                && axisCode.equals(other.axisCode)
                && valueCode.equals(other.valueCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(axisSortOrder, axisCode, valueCode);
    }
}
