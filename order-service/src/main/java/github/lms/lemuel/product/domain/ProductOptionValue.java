package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.util.Objects;

/**
 * 상품이 실제로 노출하는 옵션 값 — 표준값 전체가 아니라 이 상품이 파는 부분집합.
 *
 * <p>"사이즈 축에 XS~3XL 이 있어도 이 상품은 M·L 만 판다" 를 표현한다. 표준값을 상품마다 복제하지
 * 않으므로 값 이름은 {@link OptionAxisValue} 한 곳에만 존재한다.
 */
public final class ProductOptionValue {

    private Long id;
    private final Long productOptionAxisId;
    private final Long axisValueId;
    private int sortOrder;
    private boolean active;

    private ProductOptionValue(Long id, Long productOptionAxisId, Long axisValueId,
                               int sortOrder, boolean active) {
        this.id = id;
        this.productOptionAxisId = productOptionAxisId;
        this.axisValueId = axisValueId;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    public static ProductOptionValue create(Long productOptionAxisId, Long axisValueId, int sortOrder) {
        Objects.requireNonNull(productOptionAxisId, "productOptionAxisId");
        Objects.requireNonNull(axisValueId, "axisValueId");
        validateSortOrder(sortOrder);
        return new ProductOptionValue(null, productOptionAxisId, axisValueId, sortOrder, true);
    }

    public static ProductOptionValue rehydrate(Long id, Long productOptionAxisId, Long axisValueId,
                                               int sortOrder, boolean active) {
        return new ProductOptionValue(id, productOptionAxisId, axisValueId, sortOrder, active);
    }

    private static void validateSortOrder(int sortOrder) {
        if (sortOrder < 0) {
            throw new ProductInvariantViolationException("정렬 순서는 0 이상이어야 합니다");
        }
    }

    public void changeSortOrder(int newSortOrder) {
        validateSortOrder(newSortOrder);
        this.sortOrder = newSortOrder;
    }

    public void activate() {
        this.active = true;
    }

    /**
     * 노출 중단. 이미 이 값을 쓰는 SKU 는 남는다 — 값 삭제가 아니라 <b>신규 선택 차단</b>이라
     * 과거 주문의 복원 가능성이 유지된다.
     */
    public void deactivate() {
        this.active = false;
    }

    public boolean belongsTo(ProductOptionAxis axis) {
        Objects.requireNonNull(axis, "axis");
        return productOptionAxisId.equals(axis.getId());
    }

    public void assignId(Long newId) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1 회만 부여 가능");
        }
        this.id = newId;
    }

    public Long getId() { return id; }
    public Long getProductOptionAxisId() { return productOptionAxisId; }
    public Long getAxisValueId() { return axisValueId; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
}
