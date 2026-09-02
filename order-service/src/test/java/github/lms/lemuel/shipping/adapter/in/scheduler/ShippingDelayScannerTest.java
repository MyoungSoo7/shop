package github.lms.lemuel.shipping.adapter.in.scheduler;

import github.lms.lemuel.common.opssignal.OpsSignal;
import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.shipping.adapter.out.persistence.ShipmentJpaEntity;
import github.lms.lemuel.shipping.adapter.out.persistence.SpringDataShipmentRepository;
import github.lms.lemuel.shipping.domain.ShippingStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShippingDelayScannerTest {

    private final github.lms.lemuel.batch.FakeBatchRunLedger ledger =
            new github.lms.lemuel.batch.FakeBatchRunLedger();

    @Mock
    SpringDataShipmentRepository repository;
    @Mock
    OpsSignalPort opsSignalPort;

    private ShippingDelayScanner scanner() {
        return new ShippingDelayScanner(repository, opsSignalPort, ledger.recorder(), 72, 21_600_000L);
    }

    private ShipmentJpaEntity shipment(long id, long orderId) {
        ShipmentJpaEntity e = mock(ShipmentJpaEntity.class);
        when(e.getId()).thenReturn(id);
        when(e.getOrderId()).thenReturn(orderId);
        return e;
    }

    @Test
    void 임계를_막_넘어선_배송마다_shipping_delayed_신호를_발행한다() {
        // 목 생성/스터빙을 when(...) 밖으로 — thenReturn 인자 안에서 다른 목을 스터빙하면
        // Mockito UnfinishedStubbingException 이 난다.
        ShipmentJpaEntity s1 = shipment(1L, 100L);
        ShipmentJpaEntity s2 = shipment(2L, 200L);
        when(repository.findNewlyDelayed(eq(ShippingStatus.IN_TRANSIT), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(s1, s2));

        scanner().scan();

        ArgumentCaptor<OpsSignal> captor = ArgumentCaptor.forClass(OpsSignal.class);
        verify(opsSignalPort, org.mockito.Mockito.times(2)).emit(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(s ->
                assertThat(s.category()).isEqualTo(OpsSignalCategory.SHIPPING_DELAYED));
        assertThat(captor.getAllValues()).extracting(OpsSignal::entityId).containsExactly("1", "2");
    }

    @Test
    void 지연_건이_없으면_발행하지_않는다() {
        when(repository.findNewlyDelayed(any(), any(), any())).thenReturn(List.of());

        scanner().scan();

        verify(opsSignalPort, never()).emit(any(OpsSignal.class));
    }

    @Test
    void 스캔은_임계_시점을_기준으로_직전_주기만큼의_창을_본다() {
        when(repository.findNewlyDelayed(any(), any(), any())).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now();

        scanner().scan();

        LocalDateTime after = LocalDateTime.now();
        ArgumentCaptor<LocalDateTime> crossedBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> crossedAfter = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).findNewlyDelayed(eq(ShippingStatus.IN_TRANSIT),
                crossedBefore.capture(), crossedAfter.capture());

        // 창의 끝은 "지금 - 임계", 폭은 스캔 주기. 시계를 읽으므로 구간으로 단정한다.
        assertThat(crossedBefore.getValue())
                .isBetween(before.minusHours(72), after.minusHours(72));
        assertThat(java.time.Duration.between(crossedAfter.getValue(), crossedBefore.getValue()))
                .isEqualTo(java.time.Duration.ofMillis(21_600_000L));
    }

    // ── 놓친 창 복구 ───────────────────────────────────────────────────────────
    // 이 배치는 다음 주기가 다음 창만 본다. 한 창을 건너뛰면 그 창의 배송은 영원히 신호가 안 난다
    // — 만료 배치처럼 "다음에 다시 잡히는" 구조가 아니라서, 재실행이 유일한 복구 수단이다.

    @Test
    void 재실행은_그_날_하루를_정확히_한_창으로_다시_훑는다() {
        when(repository.findNewlyDelayed(any(), any(), any())).thenReturn(List.of());

        scanner().rerun(java.time.LocalDate.of(2026, 8, 30), false);

        ArgumentCaptor<LocalDateTime> crossedBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> crossedAfter = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).findNewlyDelayed(eq(ShippingStatus.IN_TRANSIT),
                crossedBefore.capture(), crossedAfter.capture());

        // 8/30 00:00 (제외) ~ 8/31 00:00 (포함) — 경계가 겹치지도 비지도 않아야 연속 재실행이
        // 같은 건을 두 번 잡거나 자정 직전 건을 빠뜨리지 않는다.
        assertThat(crossedAfter.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 30, 0, 0));
        assertThat(crossedBefore.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 31, 0, 0));
    }

    @Test
    void 재실행은_발행_건수를_돌려주고_원장에_사람이_돌린_것으로_남는다() {
        ShipmentJpaEntity s1 = shipment(1L, 100L);
        when(repository.findNewlyDelayed(any(), any(), any())).thenReturn(List.of(s1));

        var outcome = scanner().rerun(java.time.LocalDate.of(2026, 8, 30), false);

        assertThat(outcome.processedCount()).isEqualTo(1);
        assertThat(outcome.isFailure()).isFalse();
        // 스캐너 자신은 원장에 적지 않는다 — 재실행 기록은 BatchRerunService 몫이라,
        // 여기서 적으면 재실행 1회가 두 줄로 남는다.
        assertThat(ledger.rows()).isEmpty();
    }

    @Test
    void dryRun_은_지원하지_않는다고_밝힌다() {
        // 발행 자체가 부수효과다. 지원하는 척하고 실제로 쏘는 것보다 거절이 낫다.
        assertThat(scanner().supportsDryRun()).isFalse();
    }

    @Test
    void 스케줄_실행은_원장에_남는다() {
        ShipmentJpaEntity s1 = shipment(1L, 100L);
        when(repository.findNewlyDelayed(any(), any(), any())).thenReturn(List.of(s1));

        scanner().scan();

        var row = ledger.only();
        assertThat(row.batchName()).isEqualTo(ShippingDelayScanner.BATCH_NAME);
        assertThat(row.processedCount()).isEqualTo(1);
        assertThat(row.triggeredBy()).isEqualTo("scheduler");
    }
}
