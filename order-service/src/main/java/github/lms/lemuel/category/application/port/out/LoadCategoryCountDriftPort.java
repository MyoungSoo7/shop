package github.lms.lemuel.category.application.port.out;

import java.util.List;

/**
 * 상품수 캐시({@code ecommerce_categories.product_count})와 정본 실계수
 * ({@code product_ecommerce_categories})의 불일치 조회.
 *
 * <p>판정 조건은 재계산({@code refreshProductCounts})과 <b>같은 식</b>이어야 한다. 둘이 갈리면
 * "점검은 깨끗한데 재계산은 행을 고치는" 상태가 되고, 그때는 어느 쪽을 믿어야 할지 알 수 없다.
 */
public interface LoadCategoryCountDriftPort {

    /** 총 불일치 건수 — 표본을 잘라도 규모는 정확히 보고한다. */
    long countDrifts();

    /** @param limit 표본 상한 — 전수 조회가 운영 DB 를 오래 잡지 않게 한다. 차이가 큰 순으로 준다. */
    List<RawCountDrift> findDrifts(int limit);

    /** 분류·판정은 도메인의 몫이라 여기서는 읽은 값 그대로 싣는다. */
    record RawCountDrift(Long categoryId, String slug, String name,
                         long cachedCount, long actualCount) { }
}
