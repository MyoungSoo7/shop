package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.CreatePaymentCommand;
import github.lms.lemuel.payment.application.port.out.LoadOrderPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreatePaymentUseCaseTest {

    @Mock SavePaymentPort savePaymentPort;
    @Mock LoadOrderPort loadOrderPort;
    @Mock PublishEventPort publishEventPort;
    @InjectMocks CreatePaymentUseCase createPaymentUseCase;

    @Test @DisplayName("결제 생성 성공")
    void create_success() {
        LoadOrderPort.OrderInfo orderInfo = new LoadOrderPort.OrderInfo(1L, new BigDecimal("15000"), "CREATED");
        when(loadOrderPort.loadOrder(1L)).thenReturn(orderInfo);
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentDomain result = createPaymentUseCase.createPayment(
                new CreatePaymentCommand(1L, "CARD"));

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(result.getAmount()).isEqualByComparingTo("15000");
        assertThat(result.getOrderId()).isEqualTo(1L);
        verify(savePaymentPort).save(any(PaymentDomain.class));
    }
}
