package github.lms.lemuel.coupon.adapter.out.persistence;

import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponLifecycleCount;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponRow;
import github.lms.lemuel.coupon.application.port.in.SearchCouponsUseCase.CouponUsageRow;
import github.lms.lemuel.coupon.application.port.out.SearchCouponsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 쿠폰 콘솔 조회 어댑터.
 *
 * <p><b>수명 상태를 SQL 에서 계산하는 이유</b>: 상태별 집계와 상태 필터가 모두 필요한데, 자바에서
 * 계산하면 필터를 걸기 위해 전 쿠폰을 메모리에 올려야 한다. 판정 규칙은 SQL 표현식 하나
 * ({@link #LIFECYCLE_EXPR})에 모아 두고 목록·집계·필터가 <b>같은 식</b>을 재사용한다 —
 * 세 곳에 흩어지면 언젠가 하나만 고쳐져 "목록엔 있는데 집계엔 없는" 쿠폰이 생긴다.
 *
 * <p>우선순위는 도메인 문서와 같다: 꺼짐 → 시작 전 → 만료 → 소진 → 활성. 운영자가 손으로 끈
 * 쿠폰을 "만료됨"으로 보여 주면 왜 안 나가는지 오해하게 되므로 INACTIVE 가 가장 앞이다.
 *
 * <p><b>테이블명을 {@code opslab.} 로 한정</b>: JdbcTemplate 은 커넥션 {@code search_path} 를
 * 따르므로 한정하지 않으면 배포 후에야 "relation does not exist" 로 터진다.
 */
@Repository
@RequiredArgsConstructor
public class CouponConsoleQueryJdbcAdapter implements SearchCouponsPort {

    /**
     * 수명 상태 판정식. {@code ?} 자리는 "지금"이며, 목록·집계가 같은 값을 받는다.
     *
     * <p>{@code max_uses <= 0} 은 무제한을 뜻하므로 소진 판정에서 제외한다 — 무제한 쿠폰을
     * EXHAUSTED 로 읽으면 살아 있는 쿠폰이 목록에서 통째로 사라진다.
     */
    private static final String LIFECYCLE_EXPR = """
            CASE
                WHEN c.is_active = FALSE THEN 'INACTIVE'
                WHEN c.starts_at IS NOT NULL AND c.starts_at > ? THEN 'SCHEDULED'
                WHEN c.expires_at IS NOT NULL AND c.expires_at < ? THEN 'EXPIRED'
                WHEN c.max_uses > 0 AND c.used_count >= c.max_uses THEN 'EXHAUSTED'
                ELSE 'ACTIVE'
            END
            """;

    private static final RowMapper<CouponRow> ROW_MAPPER = (rs, rowNum) -> new CouponRow(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("type"),
            rs.getBigDecimal("discount_value"),
            rs.getBigDecimal("min_order_amount"),
            rs.getBigDecimal("max_discount_amount"),
            rs.getInt("max_uses"),
            rs.getInt("used_count"),
            rs.getString("target_type"),
            rs.getObject("target_id") == null ? null : rs.getLong("target_id"),
            toLocalDateTime(rs.getTimestamp("starts_at")),
            toLocalDateTime(rs.getTimestamp("expires_at")),
            rs.getBoolean("is_active"),
            rs.getString("lifecycle"),
            toLocalDateTime(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<CouponRow> search(CouponCriteria criteria, int page, int size) {
        List<Object> args = new ArrayList<>();
        Timestamp now = Timestamp.valueOf(criteria.now());
        args.add(now);
        args.add(now);
        String where = buildWhere(criteria, args);
        args.add(size);
        args.add(page * size);

        return jdbcTemplate.query("""
                SELECT c.id, c.code, c.type, c.discount_value, c.min_order_amount,
                       c.max_discount_amount, c.max_uses, c.used_count, c.target_type, c.target_id,
                       c.starts_at, c.expires_at, c.is_active, c.created_at,
                """ + LIFECYCLE_EXPR + """
                 AS lifecycle
                 FROM opslab.coupons c
                """ + where + """
                 ORDER BY c.created_at DESC, c.id DESC
                 LIMIT ? OFFSET ?
                """, ROW_MAPPER, args.toArray());
    }

    @Override
    public long count(CouponCriteria criteria) {
        // SELECT 절에 판정식이 없으므로 "지금" 바인딩을 미리 넣지 않는다 — 넣으면 WHERE 의
        // 물음표와 인자가 두 칸 어긋나 조건이 통째로 엉킨다.
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM opslab.coupons c" + where, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    @Override
    public List<CouponLifecycleCount> countByLifecycle(CouponCriteria criteria) {
        // SELECT 절의 판정식이 "지금"을 두 번 쓴다. WHERE 쪽 바인딩은 buildWhere 가 이어 붙인다.
        List<Object> args = new ArrayList<>();
        Timestamp now = Timestamp.valueOf(criteria.now());
        args.add(now);
        args.add(now);
        String where = buildWhere(criteria, args);

        return jdbcTemplate.query(
                "SELECT " + LIFECYCLE_EXPR + " AS lifecycle, COUNT(*) AS cnt FROM opslab.coupons c"
                        + where + " GROUP BY 1 ORDER BY cnt DESC, 1 ASC",
                (rs, rowNum) -> new CouponLifecycleCount(rs.getString("lifecycle"), rs.getLong("cnt")),
                args.toArray());
    }

    @Override
    public List<CouponUsageRow> usages(Long couponId, int limit) {
        return jdbcTemplate.query("""
                SELECT cu.id, cu.user_id, u.email AS user_email, cu.order_id, cu.used_at,
                       cu.revoked_at, cu.revoke_reason
                FROM opslab.coupon_usages cu
                LEFT JOIN opslab.users u ON u.id = cu.user_id
                WHERE cu.coupon_id = ?
                ORDER BY cu.used_at DESC, cu.id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new CouponUsageRow(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("user_email"),
                        rs.getObject("order_id") == null ? null : rs.getLong("order_id"),
                        toLocalDateTime(rs.getTimestamp("used_at")),
                        toLocalDateTime(rs.getTimestamp("revoked_at")),
                        rs.getString("revoke_reason")),
                couponId, limit);
    }

    /**
     * 값이 있는 조건만 WHERE 절로 조립한다.
     *
     * <p>수명 상태 필터는 SELECT 의 별칭을 WHERE 에서 쓸 수 없으므로 판정식을 다시 적는다.
     * 그래서 {@code now} 바인딩이 필터를 쓸 때만 두 번 더 붙는다 — 호출부가 인자 순서를
     * 맞출 수 있도록 이 메서드가 args 에 직접 추가한다.
     */
    private static String buildWhere(CouponCriteria criteria, List<Object> args) {
        List<String> clauses = new ArrayList<>();

        if (criteria.lifecycle() != null) {
            clauses.add("(" + LIFECYCLE_EXPR + ") = ?");
            Timestamp now = Timestamp.valueOf(criteria.now());
            args.add(now);
            args.add(now);
            args.add(criteria.lifecycle());
        }
        if (criteria.code() != null) {
            clauses.add("c.code ILIKE ?");
            args.add("%" + criteria.code() + "%");
        }
        if (criteria.type() != null) {
            clauses.add("c.type = ?");
            args.add(criteria.type());
        }
        if (criteria.from() != null) {
            clauses.add("c.created_at >= ?");
            args.add(Timestamp.valueOf(criteria.from()));
        }
        if (criteria.toExclusive() != null) {
            clauses.add("c.created_at < ?");
            args.add(Timestamp.valueOf(criteria.toExclusive()));
        }

        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
