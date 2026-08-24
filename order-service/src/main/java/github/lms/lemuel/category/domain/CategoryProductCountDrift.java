package github.lms.lemuel.category.domain;

import github.lms.lemuel.category.domain.exception.CategoryInvariantViolationException;

/**
 * 카테고리 상품수 캐시와 정본 실계수가 어긋난 한 건.
 *
 * <p>{@code ecommerce_categories.product_count} 는 캐시고 정본은 {@code product_ecommerce_categories}
 * 의 실계수다. 캐시를 둔 이유는 트리에 상품수 뱃지를 다는 순간 매번 세기 비싸기 때문인데, 캐시는
 * 갱신을 한 번 빠뜨리면 조용히 틀린다 — 화면은 여전히 숫자를 보여 주므로 아무도 모른다.
 *
 * <p>그래서 이 드리프트는 "언젠가 재계산하면 되는 것"이 아니라 <b>주기적으로 세어야 하는 것</b>이다.
 * 재계산 자체는 이미 있는 경로({@code POST /admin/categories/refresh-counts})가 하고, 이 값 객체는
 * 무엇이 얼마나 어긋났는지를 드러내는 몫만 진다.
 */
public record CategoryProductCountDrift(Long categoryId, String slug, String name,
                                        long cachedCount, long actualCount,
                                        CategoryCountDriftKind kind) {

    /** 어긋나지 않은 두 값으로는 만들 수 없다 — 정상 행이 목록에 섞이면 건수가 거짓이 된다. */
    public static CategoryProductCountDrift of(Long categoryId, String slug, String name,
                                               long cachedCount, long actualCount) {
        if (categoryId == null) {
            throw new CategoryInvariantViolationException("카테고리 id 가 없습니다");
        }
        if (cachedCount < 0 || actualCount < 0) {
            throw new CategoryInvariantViolationException(
                    "상품수는 음수일 수 없습니다: categoryId=" + categoryId
                            + ", 캐시=" + cachedCount + ", 실계수=" + actualCount);
        }
        if (cachedCount == actualCount) {
            throw new CategoryInvariantViolationException(
                    "캐시와 실계수가 같아 드리프트가 아닙니다: categoryId=" + categoryId);
        }
        return new CategoryProductCountDrift(categoryId, slug, name, cachedCount, actualCount,
                cachedCount > actualCount
                        ? CategoryCountDriftKind.OVERCOUNT
                        : CategoryCountDriftKind.UNDERCOUNT);
    }

    /** 캐시 − 실계수. 부호가 곧 방향이고, 절대값이 조치 우선순위다. */
    public long difference() {
        return cachedCount - actualCount;
    }
}
