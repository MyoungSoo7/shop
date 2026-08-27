package github.lms.lemuel.marketing.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface LuckyboxPrizeJpaRepository extends JpaRepository<LuckyboxPrizeJpaEntity, UUID> {

    List<LuckyboxPrizeJpaEntity> findByCampaignIdOrderByDisplayOrderAsc(UUID campaignId);

    /**
     * 경품 한 개를 예약한다. 반환값은 갱신된 행 수 — 1 이면 성공, 0 이면 소진.
     *
     * <p>수량 확인과 차감이 <b>한 문장</b>이다. WHERE 절이 조건을 들고 있으므로, 읽고 나서 쓰기까지의
     * 틈이 없다. 두 요청이 동시에 마지막 한 개를 노리면 한쪽만 1 을 받는다.
     *
     * <p>일일 수량은 별도 테이블이 아니라 이 행의 카운터 두 개로 센다. 날짜가 넘어가면 CASE 가
     * 1 로 되돌린다 — 날짜별 행을 따로 두면 "없으면 만들고 있으면 올린다" 가 필요한데, PostgreSQL 에서
     * 유니크 위반은 트랜잭션 전체를 중단시켜 JPA 만으로는 재시도가 불가능하다. 네이티브 SQL 로
     * {@code ON CONFLICT} 를 쓰는 방법도 있지만, 네이티브 쿼리는 Hibernate 의
     * {@code default_schema} 를 적용받지 못해 {@code public} 을 뒤진다.
     *
     * <p>날짜별 지급 이력이 필요하면 {@code luckybox_draws} 를 {@code (prize_id, drawn_on)} 으로 세면
     * 된다 — 그 인덱스는 V1 에 있다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LuckyboxPrizeJpaEntity p
               SET p.issuedCount = p.issuedCount + 1,
                   p.dailyIssuedCount = CASE WHEN p.dailyIssuedDate = :today THEN p.dailyIssuedCount + 1 ELSE 1 END,
                   p.dailyIssuedDate = :today
             WHERE p.id = :id
               AND p.active = true
               AND (p.totalQuota IS NULL OR p.issuedCount < p.totalQuota)
               AND (p.dailyQuota IS NULL
                    OR p.dailyIssuedDate IS NULL
                    OR p.dailyIssuedDate <> :today
                    OR p.dailyIssuedCount < p.dailyQuota)
            """)
    int tryReserve(@Param("id") UUID id, @Param("today") LocalDate today);
}
