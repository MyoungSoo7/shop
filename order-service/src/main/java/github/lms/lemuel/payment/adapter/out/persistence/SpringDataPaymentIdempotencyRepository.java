package github.lms.lemuel.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataPaymentIdempotencyRepository
        extends JpaRepository<PaymentIdempotencyJpaEntity, String> {

    /**
     * 멱등 레코드 INSERT. 동일 키가 이미 있으면 PK 위반 → {@code DataIntegrityViolationException}.
     *
     * <p>{@code save()}(merge=UPDATE) 가 아니라 네이티브 INSERT 를 쓰는 이유는 order 쪽과 같다 —
     * 중복 키에서 반드시 제약 위반이 나야 같은 트랜잭션의 결제 생성까지 롤백되어 최종 1건이 된다.
     *
     * <p>스키마 한정({@code opslab.})은 생략 불가다. Hibernate 의 {@code default_schema} 는 JPQL 에만
     * 적용되고 네이티브 쿼리는 세션 {@code search_path} 를 따르므로, 미한정 시 배포 환경에서만 터진다.
     */
    @Modifying
    @Query(value = "INSERT INTO opslab.payment_idempotency (idempotency_key, payment_id) VALUES (:key, :paymentId)",
            nativeQuery = true)
    void insert(@Param("key") String key, @Param("paymentId") Long paymentId);
}
