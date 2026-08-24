package github.lms.lemuel.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataOrderIdempotencyRepository
        extends JpaRepository<OrderIdempotencyJpaEntity, String> {

    /**
     * 멱등 레코드 INSERT. 동일 키가 이미 있으면 PK 위반 → {@code DataIntegrityViolationException}.
     *
     * <p>{@code save()}(merge=UPDATE) 가 아니라 네이티브 INSERT 를 쓰는 이유: 중복 키에서 반드시
     * 제약 위반이 나야 같은 트랜잭션의 주문 생성까지 롤백되어 최종 1건이 보장된다. created_at 은 DB DEFAULT.
     */
    @Modifying
    @Query(value = "INSERT INTO opslab.order_idempotency (idempotency_key, order_id) VALUES (:key, :orderId)",
            nativeQuery = true)
    void insert(@Param("key") String key, @Param("orderId") Long orderId);
}
