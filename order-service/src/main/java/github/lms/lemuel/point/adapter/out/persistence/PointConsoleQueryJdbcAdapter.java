package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.ExpiringLotView;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointAccountRow;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointEarnPolicyView;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointEntryView;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointLedgerTotals;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointLotView;
import github.lms.lemuel.point.application.port.out.PointConsoleQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 포인트 콘솔 조회 어댑터 — 읽기 전용 집계라 JPA 를 거치지 않고 SQL 로 직접 읽는다.
 *
 * <p><b>왜 JdbcTemplate 인가</b>: 여기 필요한 것은 애그리거트가 아니라 합계와 목록이다. 로트 수천 개를
 * 엔티티로 올려 자바에서 더하면 메모리와 시간을 둘 다 버린다.
 *
 * <p><b>원장 누계의 부호</b>: {@code point_entries.amount} 는 항상 양수이고 방향은
 * {@code entry_type} 이 정한다(스키마 CHECK 로 강제). 그래서 누계는 SQL 에서
 * GRANT·RESTORE 는 더하고 USE·EXPIRE·REVOKE 는 빼는 식으로 계산한다 — 이 규약이 깨지면
 * 3자 대조가 통째로 거짓말이 되므로, 새 엔트리 유형이 생기면 <b>여기도 같이 고쳐야 한다</b>.
 *
 * <p>시각 컬럼은 전부 {@code TIMESTAMPTZ} 라 {@link OffsetDateTime} 으로 받는다.
 *
 * <p><b>테이블명을 {@code opslab.} 로 한정하는 이유</b>: Hibernate 의 {@code default_schema: opslab}
 * 는 JPA 경로에만 적용되고, JdbcTemplate 은 커넥션의 {@code search_path}(기본
 * {@code "$user", public})를 따른다. 운영 DB 는 데이터베이스가 {@code inter}, 스키마가
 * {@code opslab} 이라 한정하지 않으면 <b>실행 시점에</b> "relation does not exist" 로 터진다
 * (컴파일도 단위 테스트도 잡지 못한다). 같은 이유로 이 저장소의 다른 JdbcTemplate 어댑터
 * ({@code ProductFacetJdbcAdapter} 등)도 전부 한정한다.
 */
@Repository
@RequiredArgsConstructor
public class PointConsoleQueryJdbcAdapter implements PointConsoleQueryPort {

    /**
     * 엔트리 유형별 부호. 잔고를 늘리는 유형과 줄이는 유형을 SQL 한 줄에 못박아 둔다.
     * (도메인 {@code PointEntryType.increasesBalance()} 와 같은 규칙의 SQL 사본이다.)
     */
    private static final String ENTRY_NET_EXPR = """
            COALESCE(SUM(CASE WHEN e.entry_type IN ('GRANT', 'RESTORE') THEN e.amount
                              ELSE -e.amount END), 0)
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<PointAccountRow> findAccount(Long userId) {
        List<PointAccountRow> rows = jdbcTemplate.query("""
                SELECT id, status, available, locked, total
                FROM opslab.point_accounts
                WHERE user_id = ?
                """,
                (rs, rowNum) -> new PointAccountRow(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getBigDecimal("available"),
                        rs.getBigDecimal("locked"),
                        rs.getBigDecimal("total")),
                userId);
        return rows.stream().findFirst();
    }

    @Override
    public BigDecimal activeLotRemaining(Long accountId) {
        return nz(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(remaining_amount), 0)
                FROM opslab.point_lots
                WHERE account_id = ? AND status = 'ACTIVE'
                """, BigDecimal.class, accountId));
    }

    @Override
    public BigDecimal entryNet(Long accountId) {
        return nz(jdbcTemplate.queryForObject(
                "SELECT " + ENTRY_NET_EXPR + " FROM opslab.point_entries e WHERE e.account_id = ?",
                BigDecimal.class, accountId));
    }

    @Override
    public List<PointLotView> recentLots(Long accountId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, origin, original_amount, remaining_amount, status,
                       granted_at, expires_at, reference_type, reference_id
                FROM opslab.point_lots
                WHERE account_id = ?
                ORDER BY granted_at DESC, id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new PointLotView(
                        rs.getLong("id"),
                        rs.getString("origin"),
                        rs.getBigDecimal("original_amount"),
                        rs.getBigDecimal("remaining_amount"),
                        rs.getString("status"),
                        rs.getObject("granted_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getString("reference_type"),
                        rs.getString("reference_id")),
                accountId, limit);
    }

    @Override
    public List<PointEntryView> recentEntries(Long accountId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, entry_type, amount, reference_type, reference_id,
                       memo, created_by, created_at
                FROM opslab.point_entries
                WHERE account_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """,
                entryMapper(), accountId, limit);
    }

    @Override
    public List<PointEarnPolicyView> policies() {
        // 종료된 행도 함께 준다 — "그때 왜 그 요율이었나"를 설명하려면 이력이 있어야 한다.
        //
        // active 는 [effective_from, effective_to) 반열림 <b>날짜 범위만</b>으로 판정한다.
        // closed_at 을 조건에 섞으면, 종료일을 미래로 잡아 둔 정책(= 예고된 요율 변경)이 그 즉시
        // "종료"로 보인다 — 실제로는 그날까지 계속 적립에 쓰이는데도. closed_at 은 적용 여부가
        // 아니라 "언제 끊었나"의 감사 기록이라, 값으로만 함께 내보낸다.
        return jdbcTemplate.query("""
                SELECT id, scope, scope_key, earn_rate, validity_days,
                       effective_from, effective_to, reason, created_by, closed_at,
                       (effective_from <= CURRENT_DATE
                        AND (effective_to IS NULL OR effective_to > CURRENT_DATE)) AS active
                FROM opslab.point_earn_policy
                ORDER BY scope, scope_key, effective_from DESC
                """,
                (rs, rowNum) -> new PointEarnPolicyView(
                        rs.getLong("id"),
                        rs.getString("scope"),
                        rs.getString("scope_key"),
                        rs.getBigDecimal("earn_rate"),
                        rs.getInt("validity_days"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class),
                        rs.getString("reason"),
                        rs.getString("created_by"),
                        rs.getBoolean("active"),
                        rs.getObject("closed_at", OffsetDateTime.class)));
    }

    @Override
    public List<ExpiringLotView> expiringLots(OffsetDateTime until, int limit) {
        // 무기한 로트(expires_at IS NULL)는 대상이 아니다 — 부분 인덱스 idx_point_lots_expiring 과
        // 같은 조건이라 그대로 탄다.
        return jdbcTemplate.query("""
                SELECT a.user_id, l.id AS lot_id, l.origin, l.remaining_amount, l.expires_at
                FROM opslab.point_lots l
                JOIN opslab.point_accounts a ON a.id = l.account_id
                WHERE l.status = 'ACTIVE'
                  AND l.expires_at IS NOT NULL
                  AND l.expires_at < ?
                  AND l.remaining_amount > 0
                ORDER BY l.expires_at ASC, l.id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new ExpiringLotView(
                        rs.getLong("user_id"),
                        rs.getLong("lot_id"),
                        rs.getString("origin"),
                        rs.getBigDecimal("remaining_amount"),
                        rs.getObject("expires_at", OffsetDateTime.class)),
                until, limit);
    }

    @Override
    public BigDecimal expiringAmount(OffsetDateTime until) {
        return nz(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(remaining_amount), 0)
                FROM opslab.point_lots
                WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < ?
                """, BigDecimal.class, until));
    }

    @Override
    public PointLedgerTotals overallTotals() {
        // 계정별 3자 값을 한 번에 만든 뒤 바깥에서 접는다. 계정 수만큼 쿼리를 돌리면
        // 계정이 늘어날수록 콘솔이 느려진다(N+1).
        PointLedgerTotals totals = jdbcTemplate.queryForObject("""
                WITH per_account AS (
                    SELECT a.id,
                           a.total AS balance,
                           COALESCE(lot.remaining, 0) AS lot_remaining,
                           COALESCE(ent.net, 0)       AS entry_net
                    FROM opslab.point_accounts a
                    LEFT JOIN (
                        SELECT account_id, SUM(remaining_amount) AS remaining
                        FROM opslab.point_lots WHERE status = 'ACTIVE' GROUP BY account_id
                    ) lot ON lot.account_id = a.id
                    LEFT JOIN (
                        SELECT e.account_id,
                               SUM(CASE WHEN e.entry_type IN ('GRANT', 'RESTORE') THEN e.amount
                                        ELSE -e.amount END) AS net
                        FROM opslab.point_entries e GROUP BY e.account_id
                    ) ent ON ent.account_id = a.id
                )
                SELECT COUNT(*)                                   AS account_count,
                       COALESCE(SUM(balance), 0)                  AS total_balance,
                       COALESCE(SUM(lot_remaining), 0)            AS total_lot_remaining,
                       COALESCE(SUM(entry_net), 0)                AS total_entry_net,
                       COUNT(*) FILTER (
                           WHERE balance <> lot_remaining OR balance <> entry_net
                       )                                          AS drifted
                FROM per_account
                """,
                (rs, rowNum) -> new PointLedgerTotals(
                        rs.getLong("account_count"),
                        rs.getBigDecimal("total_balance"),
                        rs.getBigDecimal("total_lot_remaining"),
                        rs.getBigDecimal("total_entry_net"),
                        rs.getLong("drifted")));

        // null 분기를 두지 않는다 — GROUP BY 없는 집계라 계정이 하나도 없어도 행은 1개(전부 0)로
        // 나오고, 매퍼는 null 을 돌려주지 않는다. 빈 원장은 이미 0 으로 올바르게 표현된다.
        return totals;
    }

    private static RowMapper<PointEntryView> entryMapper() {
        return (rs, rowNum) -> new PointEntryView(
                rs.getLong("id"),
                rs.getString("entry_type"),
                rs.getBigDecimal("amount"),
                rs.getString("reference_type"),
                rs.getString("reference_id"),
                rs.getString("memo"),
                rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
