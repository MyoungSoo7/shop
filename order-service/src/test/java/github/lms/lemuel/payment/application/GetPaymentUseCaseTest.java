package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GetPaymentUseCaseTest {

    @Mock LoadPaymentPort loadPaymentPort;
    @InjectMocks GetPaymentUseCase getPaymentUseCase;

    @Test @DisplayName("결제 조회 성공")
    void getPayment_success() {
        PaymentDomain payment = PaymentDomain.rehydrate(1L, 10L, new BigDecimal("30000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "pg-tx", null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));

        PaymentDomain result = getPaymentUseCase.getPayment(1L);
        assertThat(result.getAmount()).isEqualByComparingTo("30000");
    }

    @Test @DisplayName("결제 미존재 시 예외")
    void getPayment_notFound() {
        when(loadPaymentPort.loadById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> getPaymentUseCase.getPayment(999L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test @DisplayName("주문 ID로 결제 조회 성공")
    void getPaymentByOrderId_success() {
        PaymentDomain payment = PaymentDomain.rehydrate(1L, 10L, new BigDecimal("30000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "pg-tx", null, null, null);
        when(loadPaymentPort.loadByOrderId(10L)).thenReturn(Optional.of(payment));

        PaymentDomain result = getPaymentUseCase.getPaymentByOrderId(10L);
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getOrderId()).isEqualTo(10L);
    }

    @Test @DisplayName("주문에 결제가 없으면 예외")
    void getPaymentByOrderId_notFound() {
        when(loadPaymentPort.loadByOrderId(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> getPaymentUseCase.getPaymentByOrderId(999L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test @DisplayName("findByOrderId: 결제 없으면 빈 Optional (예외 아님)")
    void findByOrderId_empty() {
        when(loadPaymentPort.loadByOrderId(999L)).thenReturn(Optional.empty());
        assertThat(getPaymentUseCase.findByOrderId(999L)).isEmpty();
    }

    @Test @DisplayName("findByOrderId: 결제 있으면 반환")
    void findByOrderId_present() {
        PaymentDomain payment = PaymentDomain.rehydrate(1L, 10L, new BigDecimal("30000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "pg-tx", null, null, null);
        when(loadPaymentPort.loadByOrderId(10L)).thenReturn(Optional.of(payment));
        assertThat(getPaymentUseCase.findByOrderId(10L)).containsSame(payment);
    }
}
