package github.lms.lemuel.giftcard.adapter.out.persistence;

import github.lms.lemuel.giftcard.domain.GiftCardHoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface GiftCardHoldRepository extends JpaRepository<GiftCardHoldJpaEntity, Long> {

    List<GiftCardHoldJpaEntity> findByReferenceTypeAndReferenceId(String referenceType,
                                                                  String referenceId);

    /**
     * 선점이 걸린 카드 id 만 <b>스칼라로</b> 읽는다.
     *
     * <p>엔티티를 적재하지 않는 것이 요점이다. 카드 잠금 <b>전에</b> 선점 엔티티를 읽어 두면 그
     * 인스턴스가 영속성 컨텍스트에 남아, 잠금을 얻은 뒤 다시 조회해도 하이버네이트가 캐시된 낡은
     * 상태를 돌려준다 — 이미 다른 트랜잭션이 해소한 선점을 ACTIVE 로 착각한다
     * (포인트 선점에서 동시성 IT 가 실제로 잡은 결함이다).
     */
    @Query("""
            select h.giftCardId from GiftCardHoldJpaEntity h
            where h.referenceType = :referenceType and h.referenceId = :referenceId
            """)
    List<Long> findCardIdsByReference(@Param("referenceType") String referenceType,
                                      @Param("referenceId") String referenceId);

    /** 카드별 활성 선점 합계 — 가용액 계산의 재료. */
    @Query("""
            select h.giftCardId, coalesce(sum(h.amount), 0) from GiftCardHoldJpaEntity h
            where h.giftCardId in :cardIds and h.status = :status
            group by h.giftCardId
            """)
    List<Object[]> sumActiveByCardIds(@Param("cardIds") Collection<Long> cardIds,
                                      @Param("status") GiftCardHoldStatus status);
}
