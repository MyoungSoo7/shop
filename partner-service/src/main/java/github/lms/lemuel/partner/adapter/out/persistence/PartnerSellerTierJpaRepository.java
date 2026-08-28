package github.lms.lemuel.partner.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 셀러 등급 스냅샷 적재/조회. */
interface PartnerSellerTierJpaRepository extends JpaRepository<PartnerSellerTierJpaEntity, Long> {

    /**
     * 등급 갱신. {@code WHERE occurred_at < EXCLUDED.occurred_at} 가 이 쿼리의 전부다.
     *
     * <p>컨슈머는 at-least-once 라 <b>옛 이벤트가 다시 온다.</b> 멱등 테이블이 같은 event_id 의
     * 재전달은 막지만, 승급 뒤에 강등 이벤트가 재처리되는 것처럼 <i>서로 다른</i> 이벤트가
     * 뒤바뀐 순서로 오는 것은 막지 못한다(파티션 키가 셀러가 아니면 순서 보장이 없다).
     * 그러면 VIP 인 셀러가 잠깐 NORMAL 로 보이고, 아무 에러도 나지 않는다. 사건시각이 더
     * 최신일 때만 쓰면 그 되감기가 구조적으로 불가능해진다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO partner.partner_seller_tiers
                (seller_id, current_tier, effective_from, reason, occurred_at, updated_at)
            VALUES
                (:sellerId, :tier, :effectiveFrom, :reason, :occurredAt, NOW())
            ON CONFLICT (seller_id) DO UPDATE SET
                current_tier   = EXCLUDED.current_tier,
                effective_from = EXCLUDED.effective_from,
                reason         = EXCLUDED.reason,
                occurred_at    = EXCLUDED.occurred_at,
                updated_at     = NOW()
            WHERE partner.partner_seller_tiers.occurred_at < EXCLUDED.occurred_at
            """, nativeQuery = true)
    void upsertIfNewer(@Param("sellerId") long sellerId,
                       @Param("tier") String tier,
                       @Param("effectiveFrom") LocalDate effectiveFrom,
                       @Param("reason") String reason,
                       @Param("occurredAt") OffsetDateTime occurredAt);

    /** 반환 순서: current_tier, effective_from */
    @Query(value = """
            SELECT t.current_tier, t.effective_from
              FROM partner.partner_seller_tiers t
             WHERE t.seller_id = :sellerId
            """, nativeQuery = true)
    List<Object[]> findTierRows(@Param("sellerId") long sellerId);
}
