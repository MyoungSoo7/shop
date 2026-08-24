package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.GetPendingStockReclaimUseCase.PendingReclaim;
import github.lms.lemuel.order.application.port.out.LoadPendingStockReclaimPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 회수 대기 재고 조회 — 배송된 물건에 환불·취소가 끝났는데 아직 돌아오지 않은 주문을 운영자에게 보여준다.
 *
 * <p>이 수량은 판매 가능 재고로도 복귀하지 않았고 고객에게는 이미 환불된 상태라, 방치하면 팔 수 있는
 * 물건이 영영 묶인다. 조회의 목적은 "얼마나 오래, 얼마나 많이 묶여 있는가"를 드러내는 것이다.
 */
@ExtendWith(MockitoExtension.class)
class GetPendingStockReclaimServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 10, 0);

    @Mock LoadPendingStockReclaimPort loadPort;
    @InjectMocks GetPendingStockReclaimService service;

    /** 배송 후 환불로 원복이 보류된 주문. */
    private Order awaiting(Long id, LocalDateTime terminalAt, OrderItem... lines) {
        Order order = Order.rehydrate(id, 42L, null, new BigDecimal("35000"),
                OrderStatus.REFUNDED, terminalAt.minusDays(3), terminalAt,
                BigDecimal.ZERO, /*shipped*/ true, /*stockRestored*/ false);
        order.replaceItems(List.of(lines));
        return order;
    }

    @Test @DisplayName("대기 건이 없으면 빈 목록")
    void empty() {
        when(loadPort.findAwaitingStockReclaim(anyInt())).thenReturn(List.of());

        assertThat(service.findPending(NOW, 100)).isEmpty();
    }

    @Test @DisplayName("주문별로 묶인 수량 합계와 라인을 함께 보여준다")
    void aggregatesQuantityPerOrder() {
        Order order = awaiting(7L, NOW.minusDays(5),
                OrderItem.newItem(100L, 500L, "SKU-1", "상품A", new BigDecimal("10000"), 2),
                OrderItem.newItem(200L, null, null, "상품B", new BigDecimal("5000"), 3));
        when(loadPort.findAwaitingStockReclaim(anyInt())).thenReturn(List.of(order));

        List<PendingReclaim> pending = service.findPending(NOW, 100);

        assertThat(pending).hasSize(1);
        PendingReclaim item = pending.get(0);
        assertThat(item.orderId()).isEqualTo(7L);
        assertThat(item.totalQuantity()).isEqualTo(5);
        assertThat(item.lines()).hasSize(2);
    }

    @Test @DisplayName("얼마나 오래 묶여 있는지(경과일)를 계산한다 — 오래된 건이 먼저 눈에 띄어야 한다")
    void computesAgingDays() {
        Order order = awaiting(7L, NOW.minusDays(5),
                OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("10000"), 1));
        when(loadPort.findAwaitingStockReclaim(anyInt())).thenReturn(List.of(order));

        assertThat(service.findPending(NOW, 100).get(0).pendingDays()).isEqualTo(5);
    }

    @Test @DisplayName("오래 묶인 순으로 정렬한다")
    void sortsByOldestFirst() {
        Order recent = awaiting(1L, NOW.minusDays(1),
                OrderItem.newItem(100L, null, null, "A", new BigDecimal("1000"), 1));
        Order old = awaiting(2L, NOW.minusDays(30),
                OrderItem.newItem(200L, null, null, "B", new BigDecimal("1000"), 1));
        when(loadPort.findAwaitingStockReclaim(anyInt())).thenReturn(List.of(recent, old));

        List<PendingReclaim> pending = service.findPending(NOW, 100);

        assertThat(pending).extracting(PendingReclaim::orderId).containsExactly(2L, 1L);
    }

    @Test @DisplayName("도메인이 회수 대기로 보지 않는 주문은 걸러낸다(조회 조건과 규칙 불일치 방어)")
    void filtersOutNonAwaiting() {
        Order restored = Order.rehydrate(3L, 42L, null, new BigDecimal("10000"),
                OrderStatus.REFUNDED, NOW.minusDays(3), NOW,
                BigDecimal.ZERO, true, /*stockRestored*/ true);
        restored.replaceItems(List.of(
                OrderItem.newItem(100L, null, null, "A", new BigDecimal("1000"), 1)));
        when(loadPort.findAwaitingStockReclaim(anyInt())).thenReturn(List.of(restored));

        assertThat(service.findPending(NOW, 100)).isEmpty();
    }

    @Test @DisplayName("종단 시각이 미래면(시계 오차) 경과일을 음수로 내지 않는다")
    void futureTimestampClampsToZero() {
        Order order = awaiting(8L, NOW.plusHours(2),
                OrderItem.newItem(100L, null, null, "A", new BigDecimal("1000"), 1));
        when(loadPort.findAwaitingStockReclaim(anyInt())).thenReturn(List.of(order));

        assertThat(service.findPending(NOW, 100).get(0).pendingDays()).isZero();
    }
}
