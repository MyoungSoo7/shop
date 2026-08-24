package github.lms.lemuel.sellertier.application.service;

import github.lms.lemuel.sellertier.application.port.in.CheckSellerTierIntegrityUseCase.TierIntegrityReport;
import github.lms.lemuel.sellertier.application.port.out.LoadTierCacheDriftPort;
import github.lms.lemuel.sellertier.application.port.out.LoadTierCacheDriftPort.RawDrift;
import github.lms.lemuel.sellertier.domain.TierCacheDriftKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 등급 캐시 정합 검사 (ADR 0031).
 *
 * <p>이 검사가 알려야 하는 것은 "몇 건 틀렸나"만이 아니라 <b>어떻게 고쳐야 하나</b>다 — 종류마다 복구
 * 절차가 다르다. 또 검사 자체가 이상 데이터 한 건에 걸려 멈추면 나머지 규모를 못 본다.
 */
@ExtendWith(MockitoExtension.class)
class CheckSellerTierIntegrityServiceTest {

    @Mock LoadTierCacheDriftPort driftPort;

    private CheckSellerTierIntegrityService service;

    @BeforeEach
    void setUp() {
        service = new CheckSellerTierIntegrityService(driftPort);
    }

    @Test @DisplayName("불일치가 없으면 healthy")
    void noDrift() {
        when(driftPort.countDrifts()).thenReturn(0L);
        when(driftPort.findDrifts(anyInt())).thenReturn(List.of());

        TierIntegrityReport report = service.check(50);

        assertThat(report.healthy()).isTrue();
        assertThat(report.drifted()).isZero();
    }

    @Test @DisplayName("종류별로 집계한다 — 복구 절차가 달라서 건수만으로는 조치할 수 없다")
    void groupsByKind() {
        when(driftPort.countDrifts()).thenReturn(3L);
        when(driftPort.findDrifts(anyInt())).thenReturn(List.of(
                new RawDrift(1L, "VIP", "NORMAL"),      // STALE
                new RawDrift(2L, "VIP", null),          // CACHE_MISSING
                new RawDrift(3L, null, "STRATEGIC")));  // AUTHORITY_MISSING

        TierIntegrityReport report = service.check(50);

        assertThat(report.byKind())
                .containsEntry(TierCacheDriftKind.CACHE_STALE.name(), 1)
                .containsEntry(TierCacheDriftKind.CACHE_MISSING.name(), 1)
                .containsEntry(TierCacheDriftKind.AUTHORITY_MISSING.name(), 1);
        assertThat(report.samples()).hasSize(3);
    }

    @Test @DisplayName("전체 건수는 표본 상한과 무관하게 보고한다 — 규모를 축소해 보고하지 않는다")
    void countIsNotCappedBySample() {
        when(driftPort.countDrifts()).thenReturn(5000L);
        when(driftPort.findDrifts(anyInt())).thenReturn(List.of(new RawDrift(1L, "VIP", "NORMAL")));

        TierIntegrityReport report = service.check(1);

        assertThat(report.drifted()).isEqualTo(5000L);
        assertThat(report.samples()).hasSize(1);
        assertThat(report.healthy()).isFalse();
    }

    @Test @DisplayName("드리프트가 아닌 행이 섞여 오면 세어서 드러내되 검사를 멈추지 않는다")
    void nonDriftRowIsCountedNotThrown() {
        when(driftPort.countDrifts()).thenReturn(2L);
        when(driftPort.findDrifts(anyInt())).thenReturn(List.of(
                new RawDrift(1L, "VIP", "VIP"),     // 조회 조건이 잘못된 경우 — 드리프트 아님
                new RawDrift(2L, "VIP", "NORMAL")));

        TierIntegrityReport report = service.check(50);

        assertThat(report.unreadable()).isEqualTo(1);
        assertThat(report.samples()).hasSize(1);
        assertThat(report.healthy()).isFalse();
    }

    @Test @DisplayName("표본 상한을 그대로 전달한다 — 전수 스캔이 운영 DB 를 오래 잡지 않게")
    void passesSampleLimit() {
        when(driftPort.countDrifts()).thenReturn(0L);
        when(driftPort.findDrifts(anyInt())).thenReturn(List.of());

        service.check(7);

        verify(driftPort).findDrifts(7);
    }

    @Test @DisplayName("불일치 0 인데 드리프트 아닌 행이 있으면 healthy 가 아니다 — 조회 조건을 의심해야 한다")
    void unreadableAloneBreaksHealthy() {
        when(driftPort.countDrifts()).thenReturn(0L);
        when(driftPort.findDrifts(anyInt())).thenReturn(List.of(new RawDrift(1L, "VIP", "VIP")));

        TierIntegrityReport report = service.check(50);

        assertThat(report.healthy()).isFalse();
    }
}
