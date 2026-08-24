package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.payment.application.port.in.AuthorizePaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuthorizePaymentUseCase implements AuthorizePaymentPort {

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final PgClientPort pgClientPort;
    private final PublishEventPort publishEventPort;

    public AuthorizePaymentUseCase(LoadPaymentPort loadPaymentPort,
                                   SavePaymentPort savePaymentPort,
                                   PgClientPort pgClientPort,
                                   PublishEventPort publishEventPort) {
        this.loadPaymentPort = loadPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.pgClientPort = pgClientPort;
        this.publishEventPort = publishEventPort;
    }

    @Override
    public PaymentDomain authorizePayment(Long paymentId) {
        PaymentDomain paymentDomain = loadPaymentPort.loadById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        // Call external PG to authorize
        String pgTransactionId = pgClientPort.authorize(
            paymentDomain.getId(),
            paymentDomain.getAmount(),
            paymentDomain.getPaymentMethod()
        );

        // Domain logic
        paymentDomain.authorize(pgTransactionId);

        // Save and publish event
        PaymentDomain savedPaymentDomain = savePaymentPort.save(paymentDomain);
        publishEventPort.publishPaymentAuthorized(savedPaymentDomain.getId());

        return savedPaymentDomain;
    }
}
