package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.ViewOrderStatusHistoryUseCase.OrderStatusTimeline;
import github.lms.lemuel.order.application.port.in.ViewOrderStatusHistoryUseCase.StatusStep;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.LoadOrderStatusHistoryPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.OrderStatusChange;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 상태 이력 타임라인의 계약.
 *
 * <p>이 화면이 존재하는 이유는 "행을 보여주는 것" 이 아니라 <b>눈으로는 안 보이는 두 가지를 보이게
 * 하는 것</b>이다 — 어디서 오래 멈췄는가, 그리고 이력을 안 남기고 상태를 바꾼 경로가 있는가.
 * 그래서 여기서 지키는 것도 그 둘이다.
 */
class ViewOrderStatusHistoryServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 3, 10, 0, 0);

    private final LoadOrderPort loadOrderPort = mock(LoadOrderPort.class);

    /** 호출된 orderId 를 기록하는 최소 구현. 목보다 이력 목록 자체가 눈에 보이는 편이 낫다. */
    private static class StubHistoryPort implements LoadOrderStatusHistoryPort {
        final List<Long> askedFor = new ArrayList<>();
        List<OrderStatusChange> answer = List.of();

        @Override
        public Optional<OrderStatus> findPreviousStatus(Long orderId, OrderStatus currentStatus) {
            throw new UnsupportedOperationException("이 테스트가 쓰지 않는 경로");
        }

        @Override
        public List<OrderStatusChange> findHistory(Long orderId) {
            askedFor.add(orderId);
            return answer;
        }
    }

    private final StubHistoryPort historyPort = new StubHistoryPort();

    private ViewOrderStatusHistoryService service() {
        return new ViewOrderStatusHistoryService(loadOrderPort, historyPort);
    }

    private void orderExists(OrderStatus status) {
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(status);
        when(loadOrderPort.findById(42L)).thenReturn(Optional.of(order));
    }

    private static OrderStatusChange change(long id, String from, String to, LocalDateTime at) {
        return new OrderStatusChange(id, 42L, from, to, "admin@lemuel", null, at);
    }

    @Test
    @DisplayName("체류 시간은 다음 전이까지의 간격이고, 마지막 칸만 지금까지의 간격이다")
    void 체류_시간() {
        orderExists(OrderStatus.SHIPPING_PENDING);
        historyPort.answer = List.of(
                change(1, null, "CREATED", T0),
                change(2, "CREATED", "PAID", T0.plusHours(1)),
                change(3, "PAID", "SHIPPING_PENDING", T0.plusHours(3)));

        List<StatusStep> steps = service().view(42L, NOW).steps();

        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).dwellSeconds()).isEqualTo(3_600L);   // 1시간
        assertThat(steps.get(1).dwellSeconds()).isEqualTo(7_200L);   // 2시간
        // 마지막 칸 = "지금 몇 초째 여기 있는가". T0+3h 부터 NOW(=T0+48h) 까지 45시간.
        assertThat(steps.get(2).dwellSeconds()).isEqualTo(45L * 3_600L);
    }

    @Test
    @DisplayName("같은 시각에 찍힌 두 전이도 음수 체류로 새지 않는다")
    void 같은_시각_전이는_0초() {
        orderExists(OrderStatus.PAID);
        // 한 트랜잭션 안에서 두 번 바뀌면 changedAt 이 같은 값으로 찍힌다. 정렬 진실은 id 다.
        historyPort.answer = List.of(
                change(1, null, "CREATED", T0),
                change(2, "CREATED", "PAID", T0));

        List<StatusStep> steps = service().view(42L, NOW).steps();

        assertThat(steps.get(0).dwellSeconds()).isZero();
        assertThat(steps.get(0).dwellSeconds()).isNotNegative();
    }

    @Test
    @DisplayName("이력의 마지막 도착 상태가 주문 상태와 다르면 드러난다 — 이력을 안 남긴 전이가 있다는 뜻")
    void 이력과_주문_상태_불일치를_드러낸다() {
        orderExists(OrderStatus.REFUNDED);
        historyPort.answer = List.of(change(1, "PAID", "SHIPPING_PENDING", T0));

        OrderStatusTimeline timeline = service().view(42L, NOW);

        assertThat(timeline.currentStatus()).isEqualTo("REFUNDED");
        assertThat(timeline.lastRecordedStatus()).isEqualTo("SHIPPING_PENDING");
        assertThat(timeline.historyMatchesOrder()).isFalse();
    }

    @Test
    @DisplayName("일치하면 일치라고 말한다")
    void 일치하면_true() {
        orderExists(OrderStatus.DELIVERED);
        historyPort.answer = List.of(change(1, "IN_TRANSIT", "DELIVERED", T0));

        assertThat(service().view(42L, NOW).historyMatchesOrder()).isTrue();
    }

    @Test
    @DisplayName("이력이 0건이면 '일치' 라고 하지 않는다 — 그 자체가 조사할 신호다")
    void 이력_0건은_불일치로_남긴다() {
        orderExists(OrderStatus.PAID);
        historyPort.answer = List.of();

        OrderStatusTimeline timeline = service().view(42L, NOW);

        assertThat(timeline.steps()).isEmpty();
        assertThat(timeline.lastRecordedStatus()).isNull();
        // 여기가 true 로 덮이면 "결제됐는데 이력이 하나도 없는 주문" 이 화면에서 정상으로 보인다.
        assertThat(timeline.historyMatchesOrder()).isFalse();
    }

    @Test
    @DisplayName("없는 주문은 빈 이력이 아니라 404 다 — 오타와 조사할 버그를 구분해야 한다")
    void 없는_주문은_던진다() {
        when(loadOrderPort.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().view(42L, NOW))
                .isInstanceOf(OrderNotFoundException.class);
        // 주문이 없으면 이력을 읽으러 가지도 않는다.
        assertThat(historyPort.askedFor).isEmpty();
    }

    @Test
    @DisplayName("지금 enum 이 모르는 상태 문자열도 그대로 보여준다 — 이력은 '그때 적힌 값' 이다")
    void 모르는_상태값도_보존한다() {
        orderExists(OrderStatus.REFUNDED);
        historyPort.answer = List.of(change(1, "PAID", "LEGACY_WEIRD_STATUS", T0));

        StatusStep step = service().view(42L, NOW).steps().get(0);

        // enum 으로 옮겼다면 여기서 던지거나(화면 전체 500) null 로 지워졌다(그 한 줄만 빈칸).
        assertThat(step.newStatus()).isEqualTo("LEGACY_WEIRD_STATUS");
        assertThat(new OrderStatusChange(1L, 42L, "PAID", "LEGACY_WEIRD_STATUS", null, null, T0)
                .newStatusAsEnum()).isEmpty();
    }

    @Test
    @DisplayName("타임라인은 기록된 순서 그대로 — 뒤집지 않는다")
    void 순서_보존() {
        orderExists(OrderStatus.IN_TRANSIT);
        historyPort.answer = List.of(
                change(1, null, "CREATED", T0),
                change(2, "CREATED", "PAID", T0.plusMinutes(5)),
                change(3, "PAID", "SHIPPING_PENDING", T0.plusMinutes(10)),
                change(4, "SHIPPING_PENDING", "IN_TRANSIT", T0.plusMinutes(15)));

        assertThat(service().view(42L, NOW).steps())
                .extracting(StatusStep::newStatus)
                .containsExactly("CREATED", "PAID", "SHIPPING_PENDING", "IN_TRANSIT");
        assertThat(historyPort.askedFor).containsExactly(42L);
    }
}
