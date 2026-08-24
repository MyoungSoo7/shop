package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointHoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface PointHoldRepository extends JpaRepository<PointHoldJpaEntity, Long> {

    Optional<PointHoldJpaEntity> findByReferenceTypeAndReferenceId(String referenceType,
                                                                   String referenceId);

    /**
     * 선점이 걸린 계정 id 만 <b>스칼라로</b> 읽는다.
     *
     * <p>엔티티를 적재하지 않는 것이 요점이다. 계정 잠금 <b>전에</b> 선점 엔티티를 한 번 읽어 두면
     * 그 인스턴스가 영속성 컨텍스트에 남아, 잠금을 얻은 뒤 다시 조회해도 하이버네이트가 <b>캐시된
     * 낡은 상태</b>를 돌려준다. 그러면 이미 다른 트랜잭션이 해소한 선점을 ACTIVE 로 착각한다.
     */
    @Query("""
            select h.accountId from PointHoldJpaEntity h
            where h.referenceType = :referenceType and h.referenceId = :referenceId
            """)
    Optional<Long> findAccountIdByReference(@Param("referenceType") String referenceType,
                                            @Param("referenceId") String referenceId);

    /**
     * 계정이 지금 잠그고 있는 총액.
     *
     * <p>{@code coalesce} 로 0 을 보장한다 — 선점이 하나도 없는 계정에서 {@code sum} 은 0 이 아니라
     * null 을 돌려주고, 그대로 나가면 3자 대조가 계정을 볼 때마다 터진다.
     */
    @Query("""
            select coalesce(sum(h.amount), 0) from PointHoldJpaEntity h
            where h.accountId = :accountId and h.status = :status
            """)
    BigDecimal sumActive(@Param("accountId") Long accountId,
                         @Param("status") PointHoldStatus status);
}
