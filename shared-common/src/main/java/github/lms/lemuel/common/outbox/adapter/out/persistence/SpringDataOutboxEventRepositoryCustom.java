package github.lms.lemuel.common.outbox.adapter.out.persistence;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 스키마 한정자를 런타임에 주입해야 하는 Outbox 네이티브 쿼리(SKIP LOCKED 폴링) 프래그먼트.
 *
 * <p>네이티브 SQL 의 FROM/UPDATE 대상 테이블은 {@code @Query} 의 {@code #{...}} SpEL 로 치환되지
 * 않으므로(Spring Data 는 네이티브 쿼리에서 구조적 위치의 빈참조 SpEL 을 평가하지 않음),
 * {@link SpringDataOutboxEventRepositoryCustomImpl} 에서 {@code EntityManager} 로 직접 조립한다.
 * 스키마명은 {@link OutboxSchema}(= {@code hibernate.default_schema}) 에서 가져온다.
 */
public interface SpringDataOutboxEventRepositoryCustom {

    /**
     * claim 후보(PENDING + 미클레임/리스만료) id 를 created_at 순으로 잠그며 선택한다.
     * {@code FOR UPDATE SKIP LOCKED} 로 다른 워커가 잠근 행은 건너뛰어, 동시 폴링 시 disjoint 분할.
     * 반드시 {@link #stampClaim} / 발행과 같은 트랜잭션 안에서 호출해야 잠금이 유지된다.
     */
    List<Long> selectClaimableIds(int limit, long leaseSeconds);

    void stampClaim(List<Long> ids, String worker, LocalDateTime now);

    void clearClaim(List<Long> ids);
}
