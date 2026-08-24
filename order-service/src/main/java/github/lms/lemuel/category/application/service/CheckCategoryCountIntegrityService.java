package github.lms.lemuel.category.application.service;

import github.lms.lemuel.category.application.port.in.CheckCategoryCountIntegrityUseCase;
import github.lms.lemuel.category.application.port.out.LoadCategoryCountDriftPort;
import github.lms.lemuel.category.application.port.out.LoadCategoryCountDriftPort.RawCountDrift;
import github.lms.lemuel.category.domain.CategoryProductCountDrift;
import github.lms.lemuel.category.domain.exception.CategoryInvariantViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 카테고리 상품수 캐시 정합 점검.
 *
 * <p>{@code product_count} 는 캐시고 정본은 매핑 테이블의 실계수다. 캐시는 갱신을 한 번 빠뜨리면
 * 조용히 틀리는데, 화면은 여전히 숫자를 보여 주므로 아무도 모른다 — 그래서 세는 일을 따로 둔다.
 *
 * <p><b>읽기 전용이다.</b> 재계산 경로가 이미 있고, 점검이 조용히 고치면 "무엇이 얼마나 어긋나
 * 있었는지" 가 사라져 갱신을 빠뜨린 경로를 되짚을 수 없다. 고치는 것은 사람의 결정으로 남긴다.
 *
 * <p>규모는 전수로 세고 표본만 자른다. 표본 수를 규모로 보고하면 "10건 상한에 10건" 같은 숫자가
 * 나와 실제로 몇 개가 어긋났는지 알 수 없다.
 */
@Service
@Transactional(readOnly = true)
public class CheckCategoryCountIntegrityService implements CheckCategoryCountIntegrityUseCase {

    private static final Logger log = LoggerFactory.getLogger(CheckCategoryCountIntegrityService.class);

    private final LoadCategoryCountDriftPort driftPort;

    public CheckCategoryCountIntegrityService(LoadCategoryCountDriftPort driftPort) {
        this.driftPort = driftPort;
    }

    @Override
    public CountIntegrityReport check(int sampleLimit) {
        long drifted = driftPort.countDrifts();

        List<CategoryProductCountDrift> samples = new ArrayList<>();
        Map<String, Integer> byKind = new LinkedHashMap<>();
        int unreadable = 0;

        for (RawCountDrift raw : driftPort.findDrifts(sampleLimit)) {
            try {
                CategoryProductCountDrift drift = CategoryProductCountDrift.of(
                        raw.categoryId(), raw.slug(), raw.name(), raw.cachedCount(), raw.actualCount());
                samples.add(drift);
                byKind.merge(drift.kind().name(), 1, Integer::sum);
            } catch (CategoryInvariantViolationException e) {
                // 드리프트가 아닌 행이 조회에 걸렸다 — 조회 조건이 재계산과 갈렸다는 신호라 세어서 드러낸다.
                unreadable++;
                log.warn("드리프트로 인정되지 않는 행: categoryId={}, 사유={}", raw.categoryId(), e.getMessage());
            }
        }

        return new CountIntegrityReport(drifted, byKind, List.copyOf(samples), unreadable);
    }
}
