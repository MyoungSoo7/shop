package github.lms.lemuel.category.domain;

import github.lms.lemuel.category.domain.exception.CategoryInvariantViolationException;

import java.util.Comparator;
import java.util.Objects;

/**
 * 편성에 담긴 상품 한 줄.
 *
 * <p>고정({@link #isPinned()})이 정렬보다 우선한다 — 운영자가 "이건 무조건 맨 앞" 이라고 못 박는 자리다.
 * 정렬 규칙을 도메인이 {@link #displayOrder()} 로 들고 있어, 화면·API·배치가 각자 정렬하다 어긋나지 않는다.
 */
public final class DisplaySectionItem {

    /** 고정 우선 → 정렬 순서 → 상품 id. 마지막 항목은 동률일 때의 결정성을 위한 것이다. */
    public static final Comparator<DisplaySectionItem> DISPLAY_ORDER =
            Comparator.comparing(DisplaySectionItem::isPinned).reversed()
                    .thenComparingInt(DisplaySectionItem::getSortOrder)
                    .thenComparing(DisplaySectionItem::getProductId);

    private final Long sectionId;
    private final Long productId;
    private int sortOrder;
    private boolean pinned;

    private DisplaySectionItem(Long sectionId, Long productId, int sortOrder, boolean pinned) {
        this.sectionId = sectionId;
        this.productId = productId;
        this.sortOrder = sortOrder;
        this.pinned = pinned;
    }

    public static DisplaySectionItem of(Long sectionId, Long productId, int sortOrder, boolean pinned) {
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(productId, "productId");
        if (sortOrder < 0) {
            throw new CategoryInvariantViolationException("정렬 순서는 0 이상이어야 합니다");
        }
        return new DisplaySectionItem(sectionId, productId, sortOrder, pinned);
    }

    public void changeSortOrder(int newSortOrder) {
        if (newSortOrder < 0) {
            throw new CategoryInvariantViolationException("정렬 순서는 0 이상이어야 합니다");
        }
        this.sortOrder = newSortOrder;
    }

    public void pin() {
        this.pinned = true;
    }

    public void unpin() {
        this.pinned = false;
    }

    /** 이 항목의 진열 우선순위 키(작을수록 앞). 고정은 정렬 순서와 무관하게 앞으로 온다. */
    public long displayOrder() {
        return (pinned ? 0L : 1L) * 1_000_000L + sortOrder;
    }

    public Long getSectionId() { return sectionId; }
    public Long getProductId() { return productId; }
    public int getSortOrder() { return sortOrder; }
    public boolean isPinned() { return pinned; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DisplaySectionItem other)) return false;
        return sectionId.equals(other.sectionId) && productId.equals(other.productId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sectionId, productId);
    }
}
