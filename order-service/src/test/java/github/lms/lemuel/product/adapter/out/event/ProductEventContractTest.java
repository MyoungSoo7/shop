package github.lms.lemuel.product.adapter.out.event;

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

import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — ProductChanged 프로젝션 이벤트가
 * lemuel.product.changed 계약 스키마를 통과해야 한다. settlement_product_view 적재가 이 계약에 의존한다.
 */
@ExtendWith(MockitoExtension.class)
class ProductEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Mock TraceContextCapture traceContextCapture;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    OutboxBackedProductEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxBackedProductEventPublisher(
                saveOutboxEventPort, OutboxJson.mapper(), traceContextCapture);
    }

    private String savedPayload() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue().getPayload();
    }

    @Test
    @DisplayName("ProductChanged 페이로드는 lemuel.product.changed 계약을 만족한다")
    void productChanged_satisfiesContract() {
        publisher.publishProductChanged(42L, "프리미엄 원두 1kg");

        EventContractValidator.assertValid("lemuel.product.changed", savedPayload());
    }
}
