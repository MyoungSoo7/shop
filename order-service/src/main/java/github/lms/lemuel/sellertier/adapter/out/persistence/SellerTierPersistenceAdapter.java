package github.lms.lemuel.sellertier.adapter.out.persistence;

import github.lms.lemuel.sellertier.application.port.out.LoadSellerNetSalesPort;
import github.lms.lemuel.sellertier.application.port.out.LoadSellerTierRosterPort;
import github.lms.lemuel.sellertier.application.port.out.LoadTierAssignmentPort;
import github.lms.lemuel.sellertier.application.port.out.LoadTierCacheDriftPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierAssignmentPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierHistoryPort;
import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.TierAssignment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 등급 정본·이력·집계 어댑터 (ADR 0031).
 *
 * <p>집계는 order 자기 DB 안에서 끝난다 — payments→orders→products 조인으로 셀러별 결제 순액을 낸다.
 * settlement 를 참조하지 않으므로 MSA 경계가 유지된다.
 *
 * <p>{@code users.seller_tier} 는 읽기 캐시다. 정본 저장과 <b>같은 트랜잭션</b>에서 동기화해,
 * 기존 소비 경로(PaymentCaptured payload 동봉·프로젝션)를 한 줄도 바꾸지 않고 착지시킨다.
 */
@Repository
public class SellerTierPersistenceAdapter
        implements LoadSellerNetSalesPort, LoadTierAssignmentPort, SaveTierAssignmentPort, SaveTierHistoryPort,
        LoadTierCacheDriftPort, LoadSellerTierRosterPort {

    private final JdbcTemplate jdbc;

    public SellerTierPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SellerNetSales> findNetSalesForLast12Months(LocalDate today, int limit) {
        return jdbc.query("""
                SELECT pr.seller_id AS seller_id,
                       SUM(p.amount - COALESCE(p.refunded_amount, 0)) AS net_12m
                  FROM opslab.payments p
                  JOIN opslab.orders   o  ON o.id  = p.order_id
                  JOIN opslab.products pr ON pr.id = o.product_id
                 WHERE p.status = 'CAPTURED'
                   AND p.captured_at >= ?::date - INTERVAL '12 months'
                   AND pr.seller_id IS NOT NULL
                 GROUP BY pr.seller_id
                 ORDER BY net_12m DESC
                 LIMIT ?
                """,
                (rs, i) -> new SellerNetSales(rs.getLong("seller_id"), rs.getBigDecimal("net_12m")),
                today, limit);
    }

    @Override
    public Optional<TierAssignment> findBySellerId(Long sellerId) {
        return jdbc.query("""
                SELECT seller_id, tier, effective_from, demotion_guard_until, consecutive_miss_count
                  FROM opslab.seller_tier_assignment WHERE seller_id = ?
                """,
                (rs, i) -> TierAssignment.rehydrate(
                        rs.getLong("seller_id"),
                        SellerTierGrade.valueOf(rs.getString("tier")),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("demotion_guard_until", LocalDate.class),
                        rs.getInt("consecutive_miss_count")),
                sellerId).stream().findFirst();
    }

    @Override
    public List<TierAssignment> findAll() {
        return jdbc.query("""
                SELECT seller_id, tier, effective_from, demotion_guard_until, consecutive_miss_count
                  FROM opslab.seller_tier_assignment
                 ORDER BY seller_id
                """,
                (rs, i) -> TierAssignment.rehydrate(
                        rs.getLong("seller_id"),
                        SellerTierGrade.valueOf(rs.getString("tier")),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("demotion_guard_until", LocalDate.class),
                        rs.getInt("consecutive_miss_count")));
    }

    @Override
    @Transactional
    public TierAssignment save(TierAssignment a) {
        jdbc.update("""
                INSERT INTO opslab.seller_tier_assignment
                    (seller_id, tier, effective_from, demotion_guard_until,
                     consecutive_miss_count, last_evaluated_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (seller_id) DO UPDATE SET
                    tier = EXCLUDED.tier,
                    effective_from = EXCLUDED.effective_from,
                    demotion_guard_until = EXCLUDED.demotion_guard_until,
                    consecutive_miss_count = EXCLUDED.consecutive_miss_count,
                    last_evaluated_at = now(),
                    updated_at = now()
                """,
                a.getSellerId(), a.getTier().name(), a.getEffectiveFrom(),
                a.getDemotionGuardUntil(), a.getConsecutiveMissCount());

        // 읽기 캐시 동기화 — 같은 트랜잭션이라 정본과 어긋난 채 커밋되지 않는다.
        jdbc.update("UPDATE opslab.users SET seller_tier = ? WHERE id = ?",
                a.getTier().name(), a.getSellerId());
        return a;
    }

    @Override
    public void append(TierHistoryEntry e) {
        jdbc.update("""
                INSERT INTO opslab.seller_tier_history
                    (seller_id, prev_tier, new_tier, reason, basis_amount,
                     basis_period_start, basis_period_end, changed_by, memo)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                e.sellerId(),
                e.prevTier() == null ? null : e.prevTier().name(),
                e.newTier().name(), e.reason().name(), e.basisAmount(),
                e.basisPeriodStart(), e.basisPeriodEnd(), e.changedBy(), e.memo());
    }

    /**
     * "셀러"의 정의: <b>상품을 가졌거나</b> 등급 정본이 있는 계정.
     *
     * <p>둘 중 하나만으로는 부족하다. 상품만 보면 상품을 모두 내린 뒤에도 등급이 남아 있는 셀러가
     * 명부에서 사라지고, 정본만 보면 아직 한 번도 산정되지 않은 신규 셀러 — 관리자가 등급을 지정하려고
     * 찾는 바로 그 사람 — 이 빠진다.
     *
     * <p>{@code SELLER} 역할 같은 것은 없다. 이 시스템에서 셀러는 {@code products.seller_id} 로만
     * 존재하므로, 정의를 여기 한 곳에 두고 명부와 집계가 같은 모집단을 보게 한다.
     */
    private static final String SELLERS_CTE = """
            WITH sellers AS (
                SELECT DISTINCT seller_id FROM opslab.products WHERE seller_id IS NOT NULL
                UNION
                SELECT seller_id FROM opslab.seller_tier_assignment
            )
            """;

    @Override
    public List<RawSellerRow> findRoster(LocalDate today, int limit) {
        return jdbc.query(SELLERS_CTE + """
                , sales AS (
                    SELECT pr.seller_id AS seller_id,
                           SUM(p.amount - COALESCE(p.refunded_amount, 0)) AS net_12m
                      FROM opslab.payments p
                      JOIN opslab.orders   o  ON o.id  = p.order_id
                      JOIN opslab.products pr ON pr.id = o.product_id
                     WHERE p.status = 'CAPTURED'
                       AND p.captured_at >= ?::date - INTERVAL '12 months'
                       AND pr.seller_id IS NOT NULL
                     GROUP BY pr.seller_id
                ), listings AS (
                    SELECT seller_id, COUNT(*) AS product_count
                      FROM opslab.products WHERE seller_id IS NOT NULL GROUP BY seller_id
                )
                SELECT s.seller_id                            AS seller_id,
                       u.email                                AS email,
                       u.name                                 AS name,
                       a.tier                                 AS tier,
                       u.seller_tier                          AS cached_tier,
                       a.effective_from                       AS effective_from,
                       a.demotion_guard_until                 AS demotion_guard_until,
                       COALESCE(a.consecutive_miss_count, 0)  AS consecutive_miss_count,
                       COALESCE(sa.net_12m, 0)                AS net_12m,
                       COALESCE(li.product_count, 0)          AS product_count
                  FROM sellers s
                  LEFT JOIN opslab.users u                  ON u.id = s.seller_id
                  LEFT JOIN opslab.seller_tier_assignment a ON a.seller_id = s.seller_id
                  LEFT JOIN sales sa                        ON sa.seller_id = s.seller_id
                  LEFT JOIN listings li                     ON li.seller_id = s.seller_id
                 ORDER BY COALESCE(sa.net_12m, 0) DESC, s.seller_id
                 LIMIT ?
                """,
                (rs, i) -> new RawSellerRow(
                        rs.getLong("seller_id"),
                        rs.getString("email"),
                        rs.getString("name"),
                        rs.getString("tier"),
                        rs.getString("cached_tier"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("demotion_guard_until", LocalDate.class),
                        rs.getInt("consecutive_miss_count"),
                        rs.getBigDecimal("net_12m"),
                        rs.getLong("product_count")),
                today, limit);
    }

    @Override
    public long countSellers() {
        Long count = jdbc.queryForObject(SELLERS_CTE + " SELECT COUNT(*) FROM sellers", Long.class);
        return count == null ? 0L : count;
    }

    /**
     * 정본과 캐시가 어긋난 셀러.
     *
     * <p>{@code IS DISTINCT FROM} 을 쓰는 이유: {@code <>} 는 한쪽이 NULL 이면 NULL(=거짓 취급)이라
     * "정본은 있는데 캐시가 빈" 가장 흔한 드리프트가 통째로 빠진다. 그러면 검사가 0건을 보고하면서
     * 정작 잡아야 할 것을 놓친다.
     *
     * <p>FULL OUTER JOIN 은 캐시만 있고 정본이 없는 행(정본 도입 전 수기 UPDATE 흔적)까지 잡기 위한 것이다.
     *
     * <p><b>마지막 조건이 없으면 검사가 무의미해진다.</b> {@code users.seller_tier} 는
     * {@code NOT NULL DEFAULT 'NORMAL'} 이라 셀러가 아닌 계정에도 값이 들어 있다. 그러면 한 번도
     * 산정된 적 없는 <b>모든 사용자</b>가 "정본 없음 vs 캐시 NORMAL" 로 드리프트에 걸린다 — 운영에서
     * 실제로 13건이 그렇게 잡혔고, 전부 셀러가 아니었다. 거짓 경보가 진짜 드리프트를 덮으면 이 검사는
     * 켜 두나 마나가 된다. "정본 없음 + 기본값 캐시" 는 어긋난 것이 아니라 <b>아직 산정되지 않은</b>
     * 계정이므로 제외한다. 반대로 정본이 있거나(고아 정본) 캐시가 기본값이 아니면(수기 변경 흔적)
     * 그대로 걸린다 — 잡아야 할 것은 하나도 놓치지 않는다.
     */
    private static final String DRIFT_FROM = """
              FROM opslab.seller_tier_assignment a
              FULL OUTER JOIN opslab.users u ON u.id = a.seller_id
             WHERE a.tier IS DISTINCT FROM u.seller_tier
               AND (a.seller_id IS NOT NULL OR u.seller_tier IS NOT NULL)
               AND NOT (a.seller_id IS NULL AND u.seller_tier = 'NORMAL')
            """;

    @Override
    public List<RawDrift> findDrifts(int limit) {
        return jdbc.query("""
                SELECT COALESCE(a.seller_id, u.id) AS seller_id,
                       a.tier         AS authoritative_tier,
                       u.seller_tier  AS cached_tier
                """ + DRIFT_FROM + " ORDER BY 1 LIMIT ?",
                (rs, i) -> new RawDrift(rs.getLong("seller_id"),
                        rs.getString("authoritative_tier"), rs.getString("cached_tier")),
                limit);
    }

    @Override
    public long countDrifts() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) " + DRIFT_FROM, Long.class);
        return count == null ? 0L : count;
    }
}
