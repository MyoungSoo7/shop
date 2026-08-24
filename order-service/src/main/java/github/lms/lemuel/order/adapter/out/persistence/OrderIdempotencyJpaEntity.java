package github.lms.lemuel.order.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 주문 멱등 레코드 — {@code Idempotency-Key → order_id} 매핑.
 *
 * <p>읽기(replay)용 매핑 엔티티. 쓰기는 dup 키에서 제약 위반을 강제하기 위해
 * {@link SpringDataOrderIdempotencyRepository#insert} 네이티브 INSERT 를 사용한다
 * (JpaRepository.save 는 기존 @Id 시 merge=UPDATE 라 UNIQUE 위반이 발생하지 않음).
 */
@Entity
@Table(name = "order_idempotency")
public class OrderIdempotencyJpaEntity {

    @Id
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected OrderIdempotencyJpaEntity() {
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getOrderId() {
        return orderId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
