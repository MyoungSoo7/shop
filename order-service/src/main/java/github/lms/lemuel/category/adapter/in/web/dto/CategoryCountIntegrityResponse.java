package github.lms.lemuel.category.adapter.in.web.dto;

import github.lms.lemuel.category.application.port.in.CheckCategoryCountIntegrityUseCase.CountIntegrityReport;
import github.lms.lemuel.category.domain.CategoryProductCountDrift;

import java.util.List;
import java.util.Map;

/**
 * 상품수 캐시 정합 점검 결과.
 *
 * <p>{@code drifted} 는 전수 규모, {@code samples} 는 상한을 건 표본이다 — 둘을 함께 내려야
 * "표본 10건" 을 규모로 착각하지 않는다. {@code healthy} 는 서버가 판정해 내린다(규모 0 <b>그리고</b>
 * 읽을 수 없는 행 0).
 */
public record CategoryCountIntegrityResponse(long drifted,
                                             boolean healthy,
                                             Map<String, Integer> byKind,
                                             List<Sample> samples,
                                             int unreadable) {

    public record Sample(Long categoryId, String slug, String name,
                         long cachedCount, long actualCount, long difference, String kind) {

        static Sample from(CategoryProductCountDrift drift) {
            return new Sample(drift.categoryId(), drift.slug(), drift.name(),
                    drift.cachedCount(), drift.actualCount(), drift.difference(), drift.kind().name());
        }
    }

    public static CategoryCountIntegrityResponse from(CountIntegrityReport report) {
        return new CategoryCountIntegrityResponse(report.drifted(), report.healthy(), report.byKind(),
                report.samples().stream().map(Sample::from).toList(), report.unreadable());
    }
}
