package github.lms.lemuel.user.adapter.out.event;

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
 * 프로듀서 계약 테스트 (ADR 0024) — UserRegistered 프로젝션 이벤트가
 * lemuel.user.registered 계약 스키마를 통과해야 한다. settlement_user_view 적재가 이 계약에 의존한다.
 */
@ExtendWith(MockitoExtension.class)
class UserEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Mock TraceContextCapture traceContextCapture;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    OutboxBackedUserEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxBackedUserEventPublisher(
                saveOutboxEventPort, OutboxJson.mapper(), traceContextCapture);
    }

    private String savedPayload() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue().getPayload();
    }

    @Test
    @DisplayName("UserRegistered 페이로드는 lemuel.user.registered 계약을 만족한다")
    void userRegistered_satisfiesContract() {
        publisher.publishUserRegistered(301L, "seller777@lemuel.io");

        EventContractValidator.assertValid("lemuel.user.registered", savedPayload());
    }

    @Test
    @DisplayName("UserRegistered(email null) 페이로드도 계약을 만족한다 — null 허용 필드")
    void userRegistered_nullEmail_satisfiesContract() {
        publisher.publishUserRegistered(301L, null);

        EventContractValidator.assertValid("lemuel.user.registered", savedPayload());
    }
}
