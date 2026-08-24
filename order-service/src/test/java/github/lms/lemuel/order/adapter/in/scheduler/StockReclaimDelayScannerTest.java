package github.lms.lemuel.order.adapter.in.scheduler;

import github.lms.lemuel.common.opssignal.OpsSignal;
import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.order.application.port.out.LoadPendingStockReclaimPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 회수 지연 신호.
 *
 * <p>배송 후 환불로 보류된 재고는 회수가 확인돼야 판매 가능 상태로 돌아온다. 회수가 오지 않으면
 * 조회 화면을 열어보기 전까지 아무도 모르므로, 임계를 넘긴 건에 운영 신호를 쏴 인시던트로 잡히게 한다.
 *
 * <p>같은 건이 매 주기 재발행되지 않도록 <b>임계를 갓 넘긴 구간</b>만 훑는다(배송 지연 스캐너와 동형).
 */
@ExtendWith(MockitoExtension.class)
class StockReclaimDelayScannerTest {

    private static final Duration THRESHOLD = Duration.ofDays(14);
    private static final Duration INTERVAL = Duration.ofHours(6);

    @Mock LoadPendingStockReclaimPort loadPort;
    @Mock OpsSignalPort opsSignalPort;

    private StockReclaimDelayScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new StockReclaimDelayScanner(loadPort, opsSignalPort, THRESHOLD, INTERVAL, 200);
    }

    private Order awaiting(Long id, LocalDateTime terminalAt, int qty) {
        Order order = Order.rehydrate(id, 42L, null, new BigDecimal("35000"),
                OrderStatus.REFUNDED, terminalAt.minusDays(3), terminalAt,
                BigDecimal.ZERO, /*shipped*/ true, /*stockRestored*/ false);
        order.replaceItems(List.of(
                OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("10000"), qty)));
        return order;
    }

    @Test @DisplayName("임계를 갓 넘긴 건이 없으면 신호를 쏘지 않는다")
    void noCandidates_noSignal() {
        when(loadPort.findStockReclaimCrossedBetween(any(), any(), anyInt())).thenReturn(List.of());

        scanner.scan();

        verifyNoInteractions(opsSignalPort);
    }

    @Test @DisplayName("조회 구간은 (임계 시각 − 스캔주기, 임계 시각] — 같은 건의 반복 발행을 막는다")
    void windowIsThresholdMinusInterval() {
        when(loadPort.findStockReclaimCrossedBetween(any(), any(), anyInt())).thenReturn(List.of());

        scanner.scan();

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(loadPort).findStockReclaimCrossedBetween(from.capture(), to.capture(), anyInt());
        // 두 값의 간격이 정확히 스캔주기인지를 "from + 주기 == to" 로 본다 — 시간대 없는 두 시각의
        // 뺄셈(Duration.between)은 하루=24시간을 암묵 가정하므로 더하기 비교로 대신한다.
        assertThat(from.getValue().plus(INTERVAL)).isEqualTo(to.getValue());
    }

    @Test @DisplayName("지연 건마다 주문·수량·임계를 담은 경고 신호를 쏜다")
    void emitsSignalPerDelayedOrder() {
        LocalDateTime crossed = LocalDateTime.now().minusDays(15);
        when(loadPort.findStockReclaimCrossedBetween(any(), any(), anyInt()))
                .thenReturn(List.of(awaiting(7L, crossed, 2)));

        scanner.scan();

        ArgumentCaptor<OpsSignal> signal = ArgumentCaptor.forClass(OpsSignal.class);
        verify(opsSignalPort).emit(signal.capture());
        OpsSignal emitted = signal.getValue();
        assertThat(emitted.category()).isEqualTo(OpsSignalCategory.STOCK_RECLAIM_DELAYED);
        assertThat(emitted.entityType()).isEqualTo("order");
        assertThat(emitted.entityId()).isEqualTo("7");
        assertThat(emitted.severity()).isEqualTo(OpsSignal.SEVERITY_WARNING);
        assertThat(emitted.attributes()).containsEntry("quantity", 2);
        assertThat(emitted.attributes()).containsKey("thresholdDays");
    }

    @Test @DisplayName("도메인이 회수 대기로 보지 않는 건은 신호 대상이 아니다")
    void filtersOutNonAwaiting() {
        Order restored = Order.rehydrate(3L, 42L, null, new BigDecimal("10000"),
                OrderStatus.REFUNDED, LocalDateTime.now().minusDays(20), LocalDateTime.now().minusDays(15),
                BigDecimal.ZERO, true, /*stockRestored*/ true);
        restored.replaceItems(List.of(
                OrderItem.newItem(100L, null, null, "A", new BigDecimal("1000"), 1)));
        when(loadPort.findStockReclaimCrossedBetween(any(), any(), anyInt())).thenReturn(List.of(restored));

        scanner.scan();

        verifyNoInteractions(opsSignalPort);
    }

    @Test @DisplayName("한 건의 발행이 실패해도 나머지 건은 계속 쏜다 — 관측 신호가 배치를 깨선 안 된다")
    void emitFailureDoesNotStopBatch() {
        LocalDateTime crossed = LocalDateTime.now().minusDays(15);
        when(loadPort.findStockReclaimCrossedBetween(any(), any(), anyInt()))
                .thenReturn(List.of(awaiting(1L, crossed, 1), awaiting(2L, crossed, 1)));
        doThrow(new RuntimeException("broker down")).doNothing().when(opsSignalPort).emit(any(OpsSignal.class));

        assertThatCode(() -> scanner.scan()).doesNotThrowAnyException();

        verify(opsSignalPort, times(2)).emit(any(OpsSignal.class));
    }

    @Test @DisplayName("조회가 실패해도 스케줄러 스레드를 죽이지 않는다")
    void survivesQueryFailure() {
        when(loadPort.findStockReclaimCrossedBetween(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("DB 연결 끊김"));

        assertThatCode(() -> scanner.scan()).doesNotThrowAnyException();
    }
}
