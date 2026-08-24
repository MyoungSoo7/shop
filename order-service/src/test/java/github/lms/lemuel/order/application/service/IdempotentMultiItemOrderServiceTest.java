package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.adapter.out.lock.InMemoryDistributedLockAdapter;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.out.DistributedLockPort;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.OrderIdempotencyPort;
import github.lms.lemuel.order.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdempotentMultiItemOrderServiceTest {

    @Mock CreateMultiItemOrderUseCase delegate;
    @Mock OrderIdempotencyPort idempotencyPort;
    @Mock LoadOrderPort loadOrderPort;
    @Mock PlatformTransactionManager txManager;

    private final DistributedLockPort lockPort = new InMemoryDistributedLockAdapter();
    private IdempotentMultiItemOrderService service;

    private final List<CreateMultiItemOrderUseCase.Line> lines =
            List.of(new CreateMultiItemOrderUseCase.Line(1L, null, 1));

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionTemplate tx = new TransactionTemplate(txManager);
        service = new IdempotentMultiItemOrderService(delegate, lockPort, idempotencyPort, loadOrderPort, tx);
    }

    private Order orderWithId(long id) {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(id);
        return order;
    }

    @Test
    @DisplayName("Idempotency-Key 없으면 기존 생성 흐름으로 위임(락·멱등 미사용)")
    void blankKey_delegatesDirectly() {
        Order created = orderWithId(100L);
        when(delegate.create(1L, lines, null)).thenReturn(created);

        Order result = service.create(1L, lines, null, "  ");

        assertThat(result).isSameAs(created);
        verify(delegate).create(1L, lines, null);
        verify(idempotencyPort, never()).save(any(), any());
    }

    @Test
    @DisplayName("키 최초 사용: 주문 생성 + 멱등 기록")
    void newKey_createsAndRecords() {
        Order created = orderWithId(100L);
        when(idempotencyPort.findOrderId("K")).thenReturn(Optional.empty());
        when(delegate.create(1L, lines, null)).thenReturn(created);

        Order result = service.create(1L, lines, null, "K");

        assertThat(result).isSameAs(created);
        verify(idempotencyPort).save("K", 100L);
    }

    @Test
    @DisplayName("키 재사용: 주문 생성 없이 기존 주문을 멱등 반환")
    void existingKey_replays() {
        Order existing = mock(Order.class);
        when(idempotencyPort.findOrderId("K")).thenReturn(Optional.of(100L));
        when(loadOrderPort.findById(100L)).thenReturn(Optional.of(existing));

        Order result = service.create(1L, lines, null, "K");

        assertThat(result).isSameAs(existing);
        verify(delegate, never()).create(any(), any(), any());
        verify(idempotencyPort, never()).save(any(), any());
    }

    @Test
    @DisplayName("락 우회 동시 중복: 멱등 INSERT 제약 위반 → 승자 주문으로 복원")
    void concurrentDuplicate_recoversWinner() {
        Order created = orderWithId(100L);
        Order winner = mock(Order.class);
        // 트랜잭션 안: 처음엔 미존재 → 생성 → save 가 제약 위반. 복원 읽기에서는 승자가 보임.
        when(idempotencyPort.findOrderId("K")).thenReturn(Optional.empty(), Optional.of(100L));
        when(delegate.create(1L, lines, null)).thenReturn(created);
        doThrow(new DataIntegrityViolationException("dup")).when(idempotencyPort).save("K", 100L);
        when(loadOrderPort.findById(100L)).thenReturn(Optional.of(winner));

        Order result = service.create(1L, lines, null, "K");

        assertThat(result).isSameAs(winner);
        verify(loadOrderPort).findById(eq(100L));
    }
}
