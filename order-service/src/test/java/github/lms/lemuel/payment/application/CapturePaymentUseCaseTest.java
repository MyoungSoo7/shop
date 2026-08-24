package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.out.*;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CapturePaymentUseCaseTest {

    @Mock LoadPaymentPort loadPaymentPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock PgClientPort pgClientPort;
    @Mock UpdateOrderStatusPort updateOrderStatusPort;
    @Mock PublishEventPort publishEventPort;
    @Mock github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort loadSellerSettlementMetaPort;
    @InjectMocks CapturePaymentUseCase capturePaymentUseCase;

    @Test @DisplayName("AUTHORIZED → CAPTURED 후 PaymentCaptured 이벤트가 outbox 로 기록된다")
    void capture_success_publishesOutboxEvent() {
        PaymentDomain payment = PaymentDomain.rehydrate(1L, 10L, new BigDecimal("30000"), BigDecimal.ZERO,
                PaymentStatus.AUTHORIZED, "CARD", "pg-tx", null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentDomain result = capturePaymentUseCase.capturePayment(1L);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verify(publishEventPort).publishPaymentCaptured(eq(1L), eq(10L), eq(new BigDecimal("30000")),
                any(), any(), any(), isNull());
        // 정산 생성은 CapturePaymentUseCase 가 직접 호출하지 않는다 —
        // Kafka 컨슈머가 이벤트 수신 후 수행하므로 여기서는 검증하지 않는다.
    }

    @Test @DisplayName("결제 미존재 시 예외")
    void capture_notFound() {
        when(loadPaymentPort.loadById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> capturePaymentUseCase.capturePayment(999L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    /**
     * 트랜잭션 경계 시나리오 1 — PG 매입 성공 후 DB 저장 실패.
     *
     * <p>PG 매입({@code pgClientPort.capture})은 외부 시스템 호출이라 DB {@code @Transactional} 밖에서
     * 이미 확정된다. 그 직후 {@code savePaymentPort.save} 가 실패하면 트랜잭션은 롤백되지만 PG 쪽 매입은
     * 남아 "PG는 매입됐는데 로컬 결제 레코드는 없는" 불일치가 생긴다 → 보상 트랜잭션(PG 취소/대사)이 필요.
     * 본 테스트는 저장 실패 이후 단계(주문 PAID 전환·이벤트 발행)가 실행되지 않음을 확인한다.
     */
    @Test @DisplayName("PG 매입 성공 후 DB 저장 실패 → 이후 단계 미도달(주문상태·이벤트), PG는 보상 필요")
    void capture_dbSaveFailsAfterPgCapture_stopsDownstream() {
        PaymentDomain payment = PaymentDomain.rehydrate(1L, 10L, new BigDecimal("30000"), BigDecimal.ZERO,
                PaymentStatus.AUTHORIZED, "CARD", "pg-tx", null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        doThrow(new DataIntegrityViolationException("결제 저장 실패"))
                .when(savePaymentPort).save(any());

        assertThatThrownBy(() -> capturePaymentUseCase.capturePayment(1L))
                .isInstanceOf(DataIntegrityViolationException.class);

        // PG 매입은 이미 외부에서 확정됨(트랜잭션 밖) → 보상 트랜잭션 대상
        verify(pgClientPort).capture("pg-tx", new BigDecimal("30000"));
        // 저장 실패 이후 단계는 실행되지 않고, @Transactional 이 결제/주문 변경을 롤백한다
        verify(updateOrderStatusPort, never()).updateOrderStatus(any(), any());
        verify(publishEventPort, never())
                .publishPaymentCaptured(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * 트랜잭션 경계 시나리오 2 — DB 저장·주문 PAID 성공 후 이벤트(outbox) 발행 실패.
     *
     * <p>이벤트 발행은 Transactional Outbox 라 같은 {@code @Transactional} 안에서 outbox_events INSERT 로
     * 이뤄진다. 따라서 발행(=outbox 기록)이 실패하면 예외가 전파되어 결제 저장·주문 PAID 전환까지 <b>함께
     * 롤백</b>된다 → "DB는 갱신됐는데 이벤트만 유실되는" 불일치가 구조적으로 불가능(원자성).
     * (실제 롤백 자체는 @Transactional 프록시가 보장하며, 본 단위 테스트는 예외 전파로 경계를 표현한다.
     * 실 DB 롤백 원자성은 CreateMultiItemOrderIT 의 쿠폰 실패 롤백 케이스가 별도로 입증한다.)
     */
    @Test @DisplayName("DB 성공 후 이벤트(outbox) 발행 실패 → 예외 전파로 결제·주문까지 원자적 롤백")
    void capture_outboxPublishFails_propagatesForAtomicRollback() {
        PaymentDomain payment = PaymentDomain.rehydrate(1L, 10L, new BigDecimal("30000"), BigDecimal.ZERO,
                PaymentStatus.AUTHORIZED, "CARD", "pg-tx", null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerSettlementMetaPort.findByPaymentId(1L)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("outbox insert 실패"))
                .when(publishEventPort)
                .publishPaymentCaptured(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> capturePaymentUseCase.capturePayment(1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("outbox");

        // 저장·주문 PAID 까지는 호출되지만, 발행 실패의 예외 전파로 같은 트랜잭션 전체가 롤백된다
        verify(savePaymentPort).save(any());
        verify(updateOrderStatusPort).updateOrderStatus(10L, "PAID");
    }
}
