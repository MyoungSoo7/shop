package github.lms.lemuel.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 결제 승인 멱등 레코드 — {@code Idempotency-Key → payment_id} 매핑.
 *
 * <p>읽기(replay)용 매핑 엔티티. 쓰기는 dup 키에서 제약 위반을 강제하기 위해
 * {@link SpringDataPaymentIdempotencyRepository#insert} 네이티브 INSERT 를 사용한다
 * ({@code JpaRepository.save} 는 기존 @Id 시 merge=UPDATE 라 UNIQUE 위반이 나지 않는다).
 */
@Entity
@Table(name = "payment_idempotency")
public class PaymentIdempotencyJpaEntity {

    @Id
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PaymentIdempotencyJpaEntity() {
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
