package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.adapter.out.lock.InMemoryDistributedLockAdapter;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.in.RecordOrderConsentUseCase;
import github.lms.lemuel.order.application.port.out.DistributedLockPort;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.OrderIdempotencyPort;
import github.lms.lemuel.order.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.assertj.core.api.Assertions.tuple;
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
        when(delegate.create(1L, lines, null, null, null)).thenReturn(created);

        Order result = service.create(1L, lines, null, "  ");

        assertThat(result).isSameAs(created);
        verify(delegate).create(1L, lines, null, null, null);
        verify(idempotencyPort, never()).save(any(), any());
    }

    @Test
    @DisplayName("키 최초 사용: 주문 생성 + 멱등 기록")
    void newKey_createsAndRecords() {
        Order created = orderWithId(100L);
        when(idempotencyPort.findOrderId("K")).thenReturn(Optional.empty());
        when(delegate.create(1L, lines, null, null, null)).thenReturn(created);

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
        // 인자 5개짜리 정본을 본다. default 오버로드(4개)로 세면 정본 호출을 놓친다 — Mockito 는
        // default 메서드 자체를 목으로 잡으므로 둘이 서로 다른 호출로 기록된다.
        verify(delegate, never()).create(any(), any(), any(), any(), any());
        verify(idempotencyPort, never()).save(any(), any());
    }

    @Test
    @DisplayName("락 우회 동시 중복: 멱등 INSERT 제약 위반 → 승자 주문으로 복원")
    void concurrentDuplicate_recoversWinner() {
        Order created = orderWithId(100L);
        Order winner = mock(Order.class);
        // 트랜잭션 안: 처음엔 미존재 → 생성 → save 가 제약 위반. 복원 읽기에서는 승자가 보임.
        when(idempotencyPort.findOrderId("K")).thenReturn(Optional.empty(), Optional.of(100L));
        when(delegate.create(1L, lines, null, null, null)).thenReturn(created);
        doThrow(new DataIntegrityViolationException("dup")).when(idempotencyPort).save("K", 100L);
        when(loadOrderPort.findById(100L)).thenReturn(Optional.of(winner));

        Order result = service.create(1L, lines, null, "K");

        assertThat(result).isSameAs(winner);
        verify(loadOrderPort).findById(eq(100L));
    }

    @Test
    @DisplayName("배송지는 멱등 래퍼를 그대로 통과해 위임 대상에 전달된다")
    void address_passesThroughToDelegate() {
        var address = new github.lms.lemuel.order.domain.ShippingAddressSnapshot(
                "홍길동", "010-1234-5678", "06236", "서울시 강남구 테헤란로 1", "3층", null);
        Order created = orderWithId(100L);
        when(idempotencyPort.findOrderId("K")).thenReturn(Optional.empty());
        when(delegate.create(1L, lines, null, address, null)).thenReturn(created);

        Order result = service.create(1L, lines, null, address, "K");

        assertThat(result).isSameAs(created);
        verify(delegate).create(1L, lines, null, address, null);
    }

    /**
     * 동의도 배송지와 같이 래퍼를 그대로 통과해야 한다. 여기서 흘리면 주문은 생기는데 동의 기록만
     * 없는 주문이 남고, 그 주문은 나중에 "제3자 제공에 동의했다"는 근거로 쓸 수 없다.
     *
     * <p>멱등 키가 있는 경로로 확인한다 — 키가 있으면 락·트랜잭션 콜백을 한 겹 더 지나가므로,
     * 인자를 흘릴 자리가 실제로 있는 쪽이다.
     */
    @Test
    @DisplayName("동의도 멱등 래퍼를 그대로 통과해 위임 대상에 전달된다")
    void consent_passesThroughToDelegate() {
        var consent = new CreateMultiItemOrderUseCase.ConsentSubmission(
                List.of(new RecordOrderConsentUseCase.Acceptance("THIRD_PARTY_DELIVERY", 2, true),
                        new RecordOrderConsentUseCase.Acceptance("MARKETING_MESSAGE", 1, false)),
                "203.0.113.7");
        Order created = orderWithId(100L);
        when(idempotencyPort.findOrderId("K")).thenReturn(Optional.empty());
        when(delegate.create(1L, lines, null, null, consent)).thenReturn(created);

        Order result = service.create(1L, lines, null, null, consent, "K");

        assertThat(result).isSameAs(created);
        // 거절한 항목까지 손대지 않고 그대로 넘어간다 — "물었고 거절했다"와 "묻지 않았다"는 다른 사실이다.
        ArgumentCaptor<CreateMultiItemOrderUseCase.ConsentSubmission> captured =
                ArgumentCaptor.forClass(CreateMultiItemOrderUseCase.ConsentSubmission.class);
        verify(delegate).create(eq(1L), eq(lines), eq(null), eq(null), captured.capture());
        assertThat(captured.getValue().acceptances())
                .extracting(RecordOrderConsentUseCase.Acceptance::termsCode,
                        RecordOrderConsentUseCase.Acceptance::agreed)
                .containsExactly(tuple("THIRD_PARTY_DELIVERY", true), tuple("MARKETING_MESSAGE", false));
        assertThat(captured.getValue().ipAddress()).isEqualTo("203.0.113.7");
    }
}
