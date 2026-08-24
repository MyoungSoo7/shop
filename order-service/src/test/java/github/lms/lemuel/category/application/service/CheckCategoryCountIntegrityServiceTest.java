package github.lms.lemuel.category.application.service;

import github.lms.lemuel.category.application.port.in.CheckCategoryCountIntegrityUseCase.CountIntegrityReport;
import github.lms.lemuel.category.application.port.out.LoadCategoryCountDriftPort;
import github.lms.lemuel.category.application.port.out.LoadCategoryCountDriftPort.RawCountDrift;
import github.lms.lemuel.category.domain.CategoryProductCountDrift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckCategoryCountIntegrityServiceTest {

    @Mock LoadCategoryCountDriftPort driftPort;
    @InjectMocks CheckCategoryCountIntegrityService service;

    @Test
    @DisplayName("어긋난 곳이 없으면 healthy")
    void healthyWhenNoDrift() {
        when(driftPort.countDrifts()).thenReturn(0L);
        when(driftPort.findDrifts(10)).thenReturn(List.of());

        CountIntegrityReport report = service.check(10);

        assertThat(report.healthy()).isTrue();
        assertThat(report.drifted()).isZero();
        assertThat(report.samples()).isEmpty();
    }

    @Test
    @DisplayName("규모는 전수로 세고 표본만 잘라 준다 — 표본 수를 규모로 보고하면 조치 우선순위가 틀어진다")
    void reportsFullScaleWithTruncatedSamples() {
        when(driftPort.countDrifts()).thenReturn(97L);
        when(driftPort.findDrifts(2)).thenReturn(List.of(
                new RawCountDrift(1L, "shoes", "신발", 12, 9),
                new RawCountDrift(2L, "bags", "가방", 4, 7)));

        CountIntegrityReport report = service.check(2);

        assertThat(report.drifted()).isEqualTo(97L);
        assertThat(report.samples()).hasSize(2);
        assertThat(report.healthy()).isFalse();
    }

    @Test
    @DisplayName("방향별로 센다 — 과다와 과소는 의심할 경로가 다르다")
    void countsByKind() {
        when(driftPort.countDrifts()).thenReturn(3L);
        when(driftPort.findDrifts(10)).thenReturn(List.of(
                new RawCountDrift(1L, "shoes", "신발", 12, 9),
                new RawCountDrift(2L, "bags", "가방", 4, 7),
                new RawCountDrift(3L, "hats", "모자", 8, 1)));

        CountIntegrityReport report = service.check(10);

        assertThat(report.byKind()).containsEntry("OVERCOUNT", 2).containsEntry("UNDERCOUNT", 1);
        assertThat(report.samples())
                .extracting(CategoryProductCountDrift::categoryId)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("드리프트가 아닌 행이 조회에 걸리면 세어서 드러내고 검사는 계속한다")
    void countsUnreadableRowsWithoutStopping() {
        when(driftPort.countDrifts()).thenReturn(2L);
        when(driftPort.findDrifts(10)).thenReturn(List.of(
                new RawCountDrift(1L, "shoes", "신발", 5, 5),   // 같은 값 — 조회 조건이 의심스럽다
                new RawCountDrift(2L, "bags", "가방", 4, 7)));

        CountIntegrityReport report = service.check(10);

        assertThat(report.unreadable()).isEqualTo(1);
        assertThat(report.samples()).hasSize(1);
        // 규모가 0 이어도 읽을 수 없는 행이 있으면 건강하지 않다
        assertThat(report.healthy()).isFalse();
    }
}
