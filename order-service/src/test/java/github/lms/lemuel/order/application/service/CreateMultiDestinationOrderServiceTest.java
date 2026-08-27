package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.adapter.out.lock.InMemoryDistributedLockAdapter;
import github.lms.lemuel.order.application.port.in.CreateMultiDestinationOrderUseCase;
import github.lms.lemuel.order.application.port.in.CreateMultiDestinationOrderUseCase.Command;
import github.lms.lemuel.order.application.port.in.CreateMultiDestinationOrderUseCase.Destination;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.out.DistributedLockPort;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.OrderIdempotencyPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 여러 곳 배송 오케스트레이션.
 *
 * <p>여기서 지키려는 것은 하나다 — <b>배송지 수를 금액에 곱하지 않는다</b>. 원본(ssg-front)의
 * 같은 기능은 장바구니 총액(상품값 + 배송비)을 배송지 수만큼 더해 청구하면서 재고는 한 벌만
 * 뺐다. 이 서비스는 스스로 금액을 계산하지 않고 배송지마다 <b>그 배송지의 라인만</b> 들고
 * 단건 주문 생성에 넘긴다. 그래서 아래 테스트들은 "무엇을 넘겼는가" 를 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateMultiDestinationOrderServiceTest {

    @Mock CreateMultiItemOrderUseCase delegate;
    @Mock OrderIdempotencyPort idempotencyPort;
    @Mock LoadOrderPort loadOrderPort;
    @Mock PlatformTransactionManager txManager;

    private final DistributedLockPort lockPort = new InMemoryDistributedLockAdapter();
    private CreateMultiDestinationOrderService service;

    private static final ShippingAddressSnapshot SEOUL = new ShippingAddressSnapshot(
            "김철수", "010-1111-2222", "06134", "서울 강남구 테헤란로 1", "101호", null);
    private static final ShippingAddressSnapshot BUSAN = new ShippingAddressSnapshot(
            "이영희", "010-3333-4444", "48058", "부산 해운대구 해운대해변로 2", null, "부재시 경비실");

    private final CreateMultiItemOrderUseCase.Line lineA =
            new CreateMultiItemOrderUseCase.Line(11L, null, 1);
    private final CreateMultiItemOrderUseCase.Line lineB =
            new CreateMultiItemOrderUseCase.Line(22L, null, 3);

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new CreateMultiDestinationOrderService(
                delegate, lockPort, idempotencyPort, loadOrderPort, new TransactionTemplate(txManager));
    }

    private Order orderWithId(long id) {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(id);
        return order;
    }

    /** 위임이 부를 때마다 서로 다른 주문을 돌려준다 — 묶음이 정말 N 건인지 세려면 필요하다. */
    private List<Order> stubDelegateReturning(long... ids) {
        List<Order> orders = new ArrayList<>();
        for (long id : ids) {
            orders.add(orderWithId(id));
        }
        when(delegate.create(anyLong(), anyList(), any(), any(), any(), anyString()))
                .thenReturn(orders.get(0), orders.subList(1, orders.size()).toArray(new Order[0]));
        return orders;
    }

    private Command command(String key) {
        return new Command(7L,
                List.of(new Destination(SEOUL, List.of(lineA)),
                        new Destination(BUSAN, List.of(lineB))),
                null, key);
    }

    @Test
    @DisplayName("배송지마다 주문 하나 — 그 배송지의 라인만 넘어간다(총액을 배송지 수만큼 곱하지 않는다)")
    void fansOutOnePerDestination() {
        stubDelegateReturning(101L, 102L);

        CreateMultiDestinationOrderUseCase.Result result = service.create(command(null));

        assertThat(result.orders()).extracting(Order::getId).containsExactly(101L, 102L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CreateMultiItemOrderUseCase.Line>> lines =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ShippingAddressSnapshot> addresses =
                ArgumentCaptor.forClass(ShippingAddressSnapshot.class);
        verify(delegate, times(2)).create(eq(7L), lines.capture(), any(),
                addresses.capture(), any(), anyString());

        // 서울에는 A 만, 부산에는 B 만. 두 배송지에 장바구니 전체가 한 번씩 들어가면 여기서 걸린다.
        assertThat(lines.getAllValues()).containsExactly(List.of(lineA), List.of(lineB));
        assertThat(addresses.getAllValues()).containsExactly(SEOUL, BUSAN);
    }

    @Test
    @DisplayName("주문들은 같은 묶음 id 를 공유한다")
    void sharesOneDestinationGroupId() {
        stubDelegateReturning(101L, 102L);

        CreateMultiDestinationOrderUseCase.Result result = service.create(command(null));

        ArgumentCaptor<String> groupIds = ArgumentCaptor.forClass(String.class);
        verify(delegate, times(2)).create(anyLong(), anyList(), any(), any(), any(), groupIds.capture());

        assertThat(groupIds.getAllValues()).hasSize(2)
                .allSatisfy(id -> assertThat(id).isNotBlank())
                .containsOnly(result.destinationGroupId());
    }

    @Test
    @DisplayName("쿠폰은 넘기지 않는다 — 한 장을 N 건에 나누는 배분 규칙이 없다")
    void neverPassesCoupon() {
        stubDelegateReturning(101L, 102L);

        service.create(command(null));

        verify(delegate, times(2)).create(anyLong(), anyList(), eq(null), any(), any(), anyString());
    }

    @Test
    @DisplayName("멱등 키 없으면 원장을 건드리지 않는다")
    void blankKey_skipsIdempotency() {
        stubDelegateReturning(101L, 102L);

        service.create(command("   "));

        verify(idempotencyPort, never()).save(any(), any());
        verify(idempotencyPort, never()).findOrderId(any());
    }

    @Test
    @DisplayName("멱등 키 최초 사용: 묶음의 첫 주문을 닻으로 적는다")
    void newKey_recordsFirstOrderAsAnchor() {
        stubDelegateReturning(101L, 102L);
        when(idempotencyPort.findOrderId("k-1")).thenReturn(Optional.empty());

        CreateMultiDestinationOrderUseCase.Result result = service.create(command("k-1"));

        assertThat(result.orders()).hasSize(2);
        verify(idempotencyPort).save("k-1", 101L);
    }

    @Test
    @DisplayName("같은 키 재요청: 닻 하나에서 묶음 전체를 되살린다(주문을 다시 만들지 않는다)")
    void sameKey_replaysWholeGroup() {
        Order anchor = mock(Order.class);
        when(anchor.getDestinationGroupId()).thenReturn("group-9");
        Order sibling = orderWithId(102L);
        when(idempotencyPort.findOrderId("k-1")).thenReturn(Optional.of(101L));
        when(loadOrderPort.findById(101L)).thenReturn(Optional.of(anchor));
        when(loadOrderPort.findByDestinationGroupId("group-9")).thenReturn(List.of(anchor, sibling));

        CreateMultiDestinationOrderUseCase.Result result = service.create(command("k-1"));

        assertThat(result.destinationGroupId()).isEqualTo("group-9");
        assertThat(result.orders()).containsExactly(anchor, sibling);
        verify(delegate, never()).create(anyLong(), anyList(), any(), any(), any(), any());
        verify(idempotencyPort, never()).save(any(), any());
    }

    @Test
    @DisplayName("그 키를 이미 단일 배송지 주문이 썼으면 중복 제출로 거절 — 한 곳짜리 묶음을 그리게 둘 수 없다")
    void sameKey_onNonGroupOrder_rejected() {
        Order anchor = mock(Order.class);
        when(anchor.getDestinationGroupId()).thenReturn(null);
        when(idempotencyPort.findOrderId("k-1")).thenReturn(Optional.of(101L));
        when(loadOrderPort.findById(101L)).thenReturn(Optional.of(anchor));

        assertThatThrownBy(() -> service.create(command("k-1")))
                .isInstanceOf(DuplicateOrderSubmissionException.class);
    }

    @Test
    @DisplayName("배송지가 한 곳이면 만들기 전에 거절한다")
    void singleDestination_rejected() {
        assertThatThrownBy(() -> new Command(7L,
                List.of(new Destination(SEOUL, List.of(lineA))), null, null))
                .isInstanceOf(OrderInvariantViolationException.class)
                .hasMessageContaining("둘 이상");
    }

    @Test
    @DisplayName("배송지 상한을 넘으면 거절한다 — 한 트랜잭션이 잠그는 상품 행이 무한정 늘지 않게")
    void tooManyDestinations_rejected() {
        List<Destination> many = new ArrayList<>();
        for (int i = 0; i <= CreateMultiDestinationOrderUseCase.MAX_DESTINATIONS; i++) {
            many.add(new Destination(SEOUL, List.of(lineA)));
        }

        assertThatThrownBy(() -> new Command(7L, many, null, null))
                .isInstanceOf(OrderInvariantViolationException.class)
                .hasMessageContaining(String.valueOf(CreateMultiDestinationOrderUseCase.MAX_DESTINATIONS));
    }

    @Test
    @DisplayName("상품 없는 배송지는 거절 — 0 원 주문이 배송 큐에 뜨는 것을 막는다")
    void destinationWithoutLines_rejected() {
        assertThatThrownBy(() -> new Destination(SEOUL, List.of()))
                .isInstanceOf(OrderInvariantViolationException.class)
                .hasMessageContaining("상품이 하나 이상");
    }

    @Test
    @DisplayName("배송지 없는 목적지는 거절")
    void destinationWithoutAddress_rejected() {
        assertThatThrownBy(() -> new Destination(null, List.of(lineA)))
                .isInstanceOf(OrderInvariantViolationException.class)
                .hasMessageContaining("배송지 필수");
    }
}
