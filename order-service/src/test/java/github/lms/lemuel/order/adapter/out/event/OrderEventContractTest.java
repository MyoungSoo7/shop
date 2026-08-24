package github.lms.lemuel.order.adapter.out.event;

import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — OrderCreated 프로젝션 이벤트가
 * lemuel.order.created 계약 스키마를 통과해야 한다. settlement_order_view 적재가 이 계약에 의존한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Mock TraceContextCapture traceContextCapture;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    OutboxBackedOrderEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxBackedOrderEventPublisher(
                saveOutboxEventPort, OutboxJson.mapper(), traceContextCapture);
    }

    private String savedPayload() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue().getPayload();
    }

    @Test
    @DisplayName("OrderCreated(전체 필드) 페이로드는 lemuel.order.created 계약을 만족한다")
    void orderCreated_fullPayload_satisfiesContract() {
        publisher.publishOrderCreated(5001L, 301L, 42L, "CREATED",
                new BigDecimal("45000"), LocalDateTime.of(2026, 7, 1, 10, 15));

        EventContractValidator.assertValid("lemuel.order.created", savedPayload());
    }

    @Test
    @DisplayName("OrderCreated(optional 생략 — productId/createdAt null, amount null→\"0\") 페이로드도 계약을 만족한다")
    void orderCreated_minimalPayload_satisfiesContract() {
        publisher.publishOrderCreated(5001L, 301L, null, "CREATED", null, null);

        EventContractValidator.assertValid("lemuel.order.created", savedPayload());
    }
}
