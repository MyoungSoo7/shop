package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import org.springframework.stereotype.Component;

/**
 * Mapper between Domain Payment and JPA PaymentJpaEntity
 * (수동 매핑 유지: PaymentDomain이 불변 객체로 setter가 없음)
 */
@Component
public class PaymentMapper {

    public PaymentDomain toDomain(PaymentJpaEntity entity) {
        return PaymentDomain.rehydrate(
            entity.getId(),
            entity.getOrderId(),
            entity.getAmount(),
            entity.getRefundedAmount(),
            PaymentStatus.valueOf(entity.getStatus()),
            entity.getPaymentMethod(),
            entity.getPgTransactionId(),
            entity.getCapturedAt(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    public PaymentJpaEntity toJpaEntity(PaymentDomain domain) {
        PaymentJpaEntity entity = new PaymentJpaEntity();
        entity.setId(domain.getId());
        entity.setOrderId(domain.getOrderId());
        entity.setAmount(domain.getAmount());
        entity.setRefundedAmount(domain.getRefundedAmount());
        entity.setStatus(domain.getStatus().name());
        entity.setPaymentMethod(domain.getPaymentMethod());
        entity.setPgTransactionId(domain.getPgTransactionId());
        entity.setCapturedAt(domain.getCapturedAt());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }
}
