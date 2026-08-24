package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.exception.InvalidOrderStateForPaymentException;
import github.lms.lemuel.payment.domain.exception.OrderNotFoundException;
import github.lms.lemuel.payment.application.port.in.CreatePaymentCommand;
import github.lms.lemuel.payment.application.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadOrderPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CreatePaymentUseCase implements CreatePaymentPort {

    private final LoadOrderPort loadOrderPort;
    private final SavePaymentPort savePaymentPort;
    private final PublishEventPort publishEventPort;

    public CreatePaymentUseCase(LoadOrderPort loadOrderPort,
                                SavePaymentPort savePaymentPort,
                                PublishEventPort publishEventPort) {
        this.loadOrderPort = loadOrderPort;
        this.savePaymentPort = savePaymentPort;
        this.publishEventPort = publishEventPort;
    }

    @Override
    public PaymentDomain createPayment(CreatePaymentCommand command) {
        // Load order information
        LoadOrderPort.OrderInfo order = loadOrderPort.loadOrder(command.getOrderId());

        if (order == null) {
            throw new OrderNotFoundException(command.getOrderId());
        }

        if (!order.isCreated()) {
            throw new InvalidOrderStateForPaymentException("Order must be in CREATED status to create payment");
        }

        // Create payment domain entity
        PaymentDomain paymentDomain = PaymentDomain.create(
            order.getId(),
            order.getAmount(),
            command.getPaymentMethod()
        );

        // Save and publish event
        PaymentDomain savedPaymentDomain = savePaymentPort.save(paymentDomain);
        publishEventPort.publishPaymentCreated(savedPaymentDomain.getId(), savedPaymentDomain.getOrderId());

        return savedPaymentDomain;
    }
}
