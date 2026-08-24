package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.payment.application.port.in.GetPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class GetPaymentUseCase implements GetPaymentPort {

    private final LoadPaymentPort loadPaymentPort;

    public GetPaymentUseCase(LoadPaymentPort loadPaymentPort) {
        this.loadPaymentPort = loadPaymentPort;
    }

    @Override
    public PaymentDomain getPayment(Long paymentId) {
        return loadPaymentPort.loadById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    @Override
    public PaymentDomain getPaymentByOrderId(Long orderId) {
        return loadPaymentPort.loadByOrderId(orderId)
            .orElseThrow(() -> new PaymentNotFoundException("주문에 대한 결제를 찾을 수 없습니다. orderId=" + orderId));
    }

    @Override
    public Optional<PaymentDomain> findByOrderId(Long orderId) {
        return loadPaymentPort.loadByOrderId(orderId);
    }
}
