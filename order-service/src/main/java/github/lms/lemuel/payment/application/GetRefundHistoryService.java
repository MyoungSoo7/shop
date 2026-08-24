package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.GetRefundHistoryUseCase;
import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class GetRefundHistoryService implements GetRefundHistoryUseCase {

    private final LoadRefundPort loadRefundPort;

    public GetRefundHistoryService(LoadRefundPort loadRefundPort) {
        this.loadRefundPort = loadRefundPort;
    }

    @Override
    public List<Refund> getRefundsByPaymentId(Long paymentId) {
        if (paymentId == null || paymentId <= 0) {
            throw new PaymentInvariantViolationException("paymentId must be positive");
        }
        return loadRefundPort.findAllByPaymentId(paymentId);
    }

    @Override
    public List<Refund> getRefundsByStatus(Refund.Status status) {
        if (status == null) {
            throw new PaymentInvariantViolationException("status required");
        }
        return loadRefundPort.findByStatus(status);
    }
}
