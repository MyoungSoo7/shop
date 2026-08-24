package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.domain.SafetyNumberStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface SpringDataSafetyNumberRepository extends JpaRepository<SafetyNumberJpaEntity, Long> {

    Optional<SafetyNumberJpaEntity> findByOrderIdAndStatus(Long orderId, SafetyNumberStatus status);

    /**
     * 풀에서 대기 중인 번호 하나를 <b>행 잠금으로</b> 집는다.
     *
     * <p>락 없이 "첫 AVAILABLE 행"을 읽으면 동시 주문 둘이 같은 번호를 집어 하나가 UNIQUE 위반으로
     * 실패한다. 잠금으로 직렬화해 그 경합을 없앤다(재고 조건부 UPDATE 와 같은 문제, 같은 처방).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SafetyNumberJpaEntity s WHERE s.status = :status ORDER BY s.id ASC")
    List<SafetyNumberJpaEntity> lockAvailable(@Param("status") SafetyNumberStatus status, Pageable pageable);

    @Query("""
            SELECT s FROM SafetyNumberJpaEntity s
             WHERE s.status = :status AND s.expiresAt < :now
             ORDER BY s.expiresAt ASC
            """)
    List<SafetyNumberJpaEntity> findExpired(@Param("status") SafetyNumberStatus status,
                                            @Param("now") OffsetDateTime now,
                                            Pageable pageable);
}
