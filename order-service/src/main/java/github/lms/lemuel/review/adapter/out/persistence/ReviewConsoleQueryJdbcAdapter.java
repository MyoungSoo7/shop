package github.lms.lemuel.review.adapter.out.persistence;

import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewRow;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewStatusCount;
import github.lms.lemuel.review.application.port.out.SearchReviewsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 리뷰 콘솔 조회 어댑터.
 *
 * <p><b>상품명·작성자 이메일을 조인해 오는 이유</b>: 목록에 ID 만 있으면 운영자는 리뷰 하나를
 * 판단하려고 매번 다른 화면을 뒤져야 한다. 조인 대상은 <b>같은 서비스·같은 스키마</b>의
 * {@code products}·{@code users} 라 MSA 경계를 넘지 않는다(정산 쪽 데이터였다면 프로젝션이
 * 필요했을 것이다).
 *
 * <p>LEFT JOIN 인 이유: 상품이나 계정이 지워져도 리뷰는 남는다. INNER JOIN 이면 그런 리뷰가
 * 목록에서 통째로 사라져, 정작 문제가 되는 글을 못 찾는다.
 *
 * <p><b>테이블명을 {@code opslab.} 로 한정</b>: JdbcTemplate 은 커넥션 {@code search_path} 를
 * 따르므로 한정하지 않으면 배포 후에야 "relation does not exist" 로 터진다.
 */
@Repository
@RequiredArgsConstructor
public class ReviewConsoleQueryJdbcAdapter implements SearchReviewsPort {

    private static final RowMapper<ReviewRow> ROW_MAPPER = (rs, rowNum) -> new ReviewRow(
            rs.getLong("id"),
            rs.getLong("product_id"),
            rs.getString("product_name"),
            rs.getLong("user_id"),
            rs.getString("user_email"),
            rs.getInt("rating"),
            rs.getString("content"),
            rs.getString("status"),
            rs.getString("hidden_reason"),
            rs.getObject("hidden_by") == null ? null : rs.getLong("hidden_by"),
            toLocalDateTime(rs.getTimestamp("hidden_at")),
            toLocalDateTime(rs.getTimestamp("created_at")));

    private static final String FROM_JOIN = """
             FROM opslab.reviews r
             LEFT JOIN opslab.products p ON p.id = r.product_id
             LEFT JOIN opslab.users u ON u.id = r.user_id
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<ReviewRow> search(ReviewCriteria criteria, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);
        args.add(size);
        args.add(page * size);

        return jdbcTemplate.query("""
                SELECT r.id, r.product_id, p.name AS product_name, r.user_id, u.email AS user_email,
                       r.rating, r.content, r.status, r.hidden_reason, r.hidden_by, r.hidden_at,
                       r.created_at
                """ + FROM_JOIN + where + """
                 ORDER BY r.created_at DESC, r.id DESC
                 LIMIT ? OFFSET ?
                """, ROW_MAPPER, args.toArray());
    }

    @Override
    public long count(ReviewCriteria criteria) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + FROM_JOIN + where, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    @Override
    public List<ReviewStatusCount> countByStatus(ReviewCriteria criteria) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);

        return jdbcTemplate.query(
                "SELECT r.status, COUNT(*) AS cnt" + FROM_JOIN + where
                        + " GROUP BY r.status ORDER BY cnt DESC, r.status ASC",
                (rs, rowNum) -> new ReviewStatusCount(rs.getString("status"), rs.getLong("cnt")),
                args.toArray());
    }

    /** 값이 있는 조건만 WHERE 절로 조립하고, 같은 순서로 바인딩 인자를 채운다. */
    private static String buildWhere(ReviewCriteria criteria, List<Object> args) {
        List<String> clauses = new ArrayList<>();

        if (criteria.keyword() != null) {
            clauses.add("r.content ILIKE ?");
            args.add("%" + criteria.keyword() + "%");
        }
        if (criteria.productId() != null) {
            clauses.add("r.product_id = ?");
            args.add(criteria.productId());
        }
        if (criteria.userId() != null) {
            clauses.add("r.user_id = ?");
            args.add(criteria.userId());
        }
        if (criteria.status() != null) {
            clauses.add("r.status = ?");
            args.add(criteria.status());
        }
        if (criteria.maxRating() != null) {
            clauses.add("r.rating <= ?");
            args.add(criteria.maxRating());
        }
        if (criteria.from() != null) {
            clauses.add("r.created_at >= ?");
            args.add(Timestamp.valueOf(criteria.from()));
        }
        if (criteria.toExclusive() != null) {
            clauses.add("r.created_at < ?");
            args.add(Timestamp.valueOf(criteria.toExclusive()));
        }

        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
