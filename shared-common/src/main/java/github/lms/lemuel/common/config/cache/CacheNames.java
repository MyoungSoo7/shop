package github.lms.lemuel.common.config.cache;

import java.util.List;

/**
 * 애플리케이션 캐시 이름 단일 정의처.
 *
 * <p>로컬 전용 Caffeine({@code CacheConfig})과 L1+L2 2-tier({@code TwoTierCacheConfig}) 양쪽이
 * 같은 이름 집합을 공유하도록 한곳에 모은다.
 */
public final class CacheNames {

    public static final String CATEGORIES = "categories";
    public static final String TAGS = "tags";
    public static final String PRODUCTS = "products";
    public static final String ECOMMERCE_CATEGORIES = "ecommerce-categories";

    public static final List<String> ALL = List.of(
            CATEGORIES, TAGS, PRODUCTS, ECOMMERCE_CATEGORIES);

    private CacheNames() {
    }
}
