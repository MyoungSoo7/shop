package github.lms.lemuel.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataPaymentTenderRepository extends JpaRepository<PaymentTenderJpaEntity, Long> {

    List<PaymentTenderJpaEntity> findByPaymentIdOrderBySequenceAsc(Long paymentId);
}
