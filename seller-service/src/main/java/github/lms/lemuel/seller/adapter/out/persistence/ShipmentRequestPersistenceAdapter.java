package github.lms.lemuel.seller.adapter.out.persistence;

import github.lms.lemuel.seller.application.port.out.ShipmentRequestPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 송장 등록 요청 원장의 영속화. */
@Component
@RequiredArgsConstructor
class ShipmentRequestPersistenceAdapter implements ShipmentRequestPort {

    private final ShipmentRequestJpaRepository repository;

    @Override
    public boolean record(long orderId, long sellerId, String carrier, String trackingNumber,
                          long requestedByUserId) {
        return repository.insertIfAbsent(orderId, sellerId, carrier, trackingNumber, requestedByUserId) > 0;
    }
}
