package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.util.Objects;

/**
 * 상품이 채택한 옵션 축 — "이 상품은 색상을 1차, 사이즈를 2차로 판다".
 *
 * <p>{@link #getSortOrder()} 가 곧 <b>차수</b>이고 상한이 없다. 선행 사례들이 옵션을 2단
 * (부모/자식 자기참조, {@code OPTCODE}/{@code OPTCODE2}) 으로 고정해 3차 옵션이 필요해진 순간
 * 스키마를 못 늘렸는데, 차수를 행으로 표현하면 그 한계가 생기지 않는다.
 */
public final class ProductOptionAxis {

    /** 자유입력(TEXT 축)의 기본 상한 — 축이 따로 정하지 않았을 때 쓴다. 컬럼 폭과 같다. */
    public static final int DEFAULT_TEXT_MAX_LENGTH = 200;

    private Long id;
    private final Long productId;
    private final Long axisId;
    private int sortOrder;
    private boolean required;
    /** TEXT 축에서 받을 최대 글자 수. null 이면 {@link #DEFAULT_TEXT_MAX_LENGTH}. 선택형 축에서는 의미 없다. */
    private final Integer textMaxLength;

    private ProductOptionAxis(Long id, Long productId, Long axisId, int sortOrder, boolean required,
                              Integer textMaxLength) {
        this.id = id;
        this.productId = productId;
        this.axisId = axisId;
        this.sortOrder = sortOrder;
        this.required = required;
        this.textMaxLength = textMaxLength;
    }

    public static ProductOptionAxis create(Long productId, Long axisId, int sortOrder, boolean required) {
        return create(productId, axisId, sortOrder, required, null);
    }

    public static ProductOptionAxis create(Long productId, Long axisId, int sortOrder, boolean required,
                                           Integer textMaxLength) {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(axisId, "axisId");
        validateSortOrder(sortOrder);
        validateTextMaxLength(textMaxLength);
        return new ProductOptionAxis(null, productId, axisId, sortOrder, required, textMaxLength);
    }

    public static ProductOptionAxis rehydrate(Long id, Long productId, Long axisId,
                                              int sortOrder, boolean required) {
        return rehydrate(id, productId, axisId, sortOrder, required, null);
    }

    public static ProductOptionAxis rehydrate(Long id, Long productId, Long axisId,
                                              int sortOrder, boolean required,
                                              Integer textMaxLength) {
        return new ProductOptionAxis(id, productId, axisId, sortOrder, required, textMaxLength);
    }

    private static void validateTextMaxLength(Integer value) {
        if (value != null && (value < 1 || value > DEFAULT_TEXT_MAX_LENGTH)) {
            throw new ProductInvariantViolationException(
                    "자유입력 상한은 1~" + DEFAULT_TEXT_MAX_LENGTH + "자여야 합니다: " + value);
        }
    }

    /** 실제로 적용할 자유입력 상한 — 축이 정하지 않았으면 기본값. */
    public int effectiveTextMaxLength() {
        return textMaxLength == null ? DEFAULT_TEXT_MAX_LENGTH : textMaxLength;
    }

    public Integer getTextMaxLength() { return textMaxLength; }

    private static void validateSortOrder(int sortOrder) {
        if (sortOrder < 0) {
            throw new ProductInvariantViolationException("옵션 차수(sortOrder)는 0 이상이어야 합니다");
        }
    }

    /** 차수 변경. 상품 내 차수 유일성은 DB UNIQUE(product_id, sort_order) 가 최종 방어선이다. */
    public void changeSortOrder(int newSortOrder) {
        validateSortOrder(newSortOrder);
        this.sortOrder = newSortOrder;
    }

    public void markRequired() {
        this.required = true;
    }

    public void markOptional() {
        this.required = false;
    }

    /** 1차 옵션인가(차수 0). */
    public boolean isFirstAxis() {
        return sortOrder == 0;
    }

    public void assignId(Long newId) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1 회만 부여 가능");
        }
        this.id = newId;
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public Long getAxisId() { return axisId; }
    public int getSortOrder() { return sortOrder; }
    public boolean isRequired() { return required; }
}
