package github.lms.lemuel.category.application.port.in;

import github.lms.lemuel.category.domain.CategoryProductCountDrift;

import java.util.List;
import java.util.Map;

/**
 * 카테고리 상품수 캐시 정합 점검.
 *
 * <p>읽기 전용이다 — 고치는 경로({@code POST /admin/categories/refresh-counts})는 이미 따로 있고,
 * 점검이 조용히 고치면 "무엇이 얼마나 어긋나 있었는지" 가 사라진다. 캐시가 왜 어긋났는지(갱신을
 * 빠뜨린 경로가 어디인지)는 숫자를 남겨 둬야 추적할 수 있다.
 */
public interface CheckCategoryCountIntegrityUseCase {

    CountIntegrityReport check(int sampleLimit);

    /**
     * @param drifted    전체 불일치 건수(표본 상한과 무관한 실제 규모)
     * @param byKind     방향별 표본 건수 — 과다/과소는 의심할 경로가 다르다
     * @param samples    차이가 큰 순 표본. {@code drifted} 보다 적을 수 있다
     * @param unreadable 도메인이 드리프트로 인정하지 않은 행 수 — 0 이 아니면 조회 조건 자체를 의심한다
     */
    record CountIntegrityReport(long drifted, Map<String, Integer> byKind,
                                List<CategoryProductCountDrift> samples, int unreadable) {

        public boolean healthy() {
            return drifted == 0 && unreadable == 0;
        }
    }
}
