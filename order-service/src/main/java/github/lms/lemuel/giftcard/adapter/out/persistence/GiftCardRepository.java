package github.lms.lemuel.giftcard.adapter.out.persistence;

import github.lms.lemuel.giftcard.domain.GiftCardStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GiftCardRepository extends JpaRepository<GiftCardJpaEntity, Long> {

    boolean existsByCodeHash(String codeHash);

    /** 등록 경로 — 코드 해시로 찾아 잠근다. 두 사람이 같은 코드를 동시에 등록하는 경합을 막는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GiftCardJpaEntity c where c.codeHash = :codeHash")
    Optional<GiftCardJpaEntity> lockByCodeHash(@Param("codeHash") String codeHash);

    /**
     * 결제 재원 — 잠근 채로 읽는다. 잔액 확인과 차감 사이에 다른 결제가 끼어들면 같은 잔액이
     * 두 번 쓰인다. <b>id 오름차순</b>이라 여러 장을 잡을 때도 교착이 나지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from GiftCardJpaEntity c
            where c.ownerUserId = :userId and c.status = :status and c.remainingAmount > 0
            order by c.id asc
            """)
    List<GiftCardJpaEntity> lockSpendable(@Param("userId") Long userId,
                                          @Param("status") GiftCardStatus status);

    /** 잔액 조회용(락 없음). */
    @Query("""
            select c from GiftCardJpaEntity c
            where c.ownerUserId = :userId and c.status = :status and c.remainingAmount > 0
            """)
    List<GiftCardJpaEntity> findSpendable(@Param("userId") Long userId,
                                          @Param("status") GiftCardStatus status);

    /** 환불 복원 — 카드 id 오름차순으로 잠근다(교착 방지). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GiftCardJpaEntity c where c.id in :ids order by c.id asc")
    List<GiftCardJpaEntity> lockByIds(@Param("ids") Collection<Long> ids);

    /** 소멸 배치 — 아직 살아 있는(활성·등록) 카드 중 만료된 것. */
    @Query("""
            select c from GiftCardJpaEntity c
            where c.status in :statuses and c.expiresAt < :at
            order by c.id asc
            """)
    List<GiftCardJpaEntity> findExpired(@Param("statuses") Collection<GiftCardStatus> statuses,
                                        @Param("at") OffsetDateTime at,
                                        Pageable pageable);
}
