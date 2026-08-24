package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberStatusCount;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberSummary;
import github.lms.lemuel.user.application.port.out.SearchMembersPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 회원 콘솔 조회 어댑터 — 목록·집계라 JPA 를 거치지 않고 SQL 로 직접 읽는다.
 *
 * <p><b>테이블명을 {@code opslab.} 로 한정하는 이유</b>: Hibernate 의 {@code default_schema: opslab}
 * 은 JPA 경로에만 적용되고, JdbcTemplate 은 커넥션의 {@code search_path} 를 따른다. 한정하지
 * 않으면 컴파일도 단위 테스트도 통과한 채 <b>배포 후에</b> "relation does not exist" 로 터진다.
 * 이 저장소의 다른 JdbcTemplate 어댑터도 전부 같은 규약이다.
 *
 * <p><b>동적 조건을 문자열로 조립하는 이유</b>: {@code (? IS NULL OR col = ?)} 관용구는 PostgreSQL
 * 에서 파라미터 타입 추론이 {@code bytea} 로 떨어져 실행 시점에 터진다. 값이 있는 조건만 붙이고
 * 그 조건에만 바인딩한다 — 조립되는 것은 상수 조각뿐이고 사용자 입력은 전부 바인딩이다.
 *
 * <p>정렬은 {@code created_at DESC, id DESC} 다. 같은 초에 가입한 두 계정의 순서가 페이지마다
 * 흔들리면 페이지를 넘길 때 같은 사람이 두 번 보이거나 사라진다.
 */
@Repository
@RequiredArgsConstructor
public class MemberConsoleQueryJdbcAdapter implements SearchMembersPort {

    private static final RowMapper<MemberSummary> ROW_MAPPER = (rs, rowNum) -> new MemberSummary(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("name"),
            rs.getString("phone_number"),
            rs.getString("role"),
            rs.getString("membership_status"),
            rs.getBoolean("is_active"),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<MemberSummary> search(MemberCriteria criteria, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);
        args.add(size);
        args.add(page * size);

        return jdbcTemplate.query("""
                SELECT id, email, name, phone_number, role, membership_status, is_active,
                       created_at, updated_at
                FROM opslab.users
                """ + where + """
                 ORDER BY created_at DESC, id DESC
                 LIMIT ? OFFSET ?
                """, ROW_MAPPER, args.toArray());
    }

    @Override
    public long count(MemberCriteria criteria) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM opslab.users " + where, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    @Override
    public List<MemberStatusCount> countByStatus(MemberCriteria criteria) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);

        return jdbcTemplate.query(
                "SELECT membership_status, COUNT(*) AS cnt FROM opslab.users " + where
                        + " GROUP BY membership_status ORDER BY cnt DESC, membership_status ASC",
                (rs, rowNum) -> new MemberStatusCount(rs.getString("membership_status"), rs.getLong("cnt")),
                args.toArray());
    }

    /** 값이 있는 조건만 WHERE 절로 조립하고, 같은 순서로 바인딩 인자를 채운다. */
    private static String buildWhere(MemberCriteria criteria, List<Object> args) {
        List<String> clauses = new ArrayList<>();

        if (criteria.keyword() != null) {
            // 이메일·이름·연락처를 함께 훑는 편의 검색. ILIKE 라 인덱스를 타지 않으므로 다른
            // 조건과 함께 쓰이는 것을 전제로 한다.
            clauses.add("(email ILIKE ? OR name ILIKE ? OR phone_number ILIKE ?)");
            String pattern = "%" + criteria.keyword() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        if (criteria.role() != null) {
            clauses.add("role = ?");
            args.add(criteria.role());
        }
        if (criteria.membershipStatus() != null) {
            clauses.add("membership_status = ?");
            args.add(criteria.membershipStatus());
        }
        if (criteria.active() != null) {
            clauses.add("is_active = ?");
            args.add(criteria.active());
        }
        if (criteria.joinedFrom() != null) {
            clauses.add("created_at >= ?");
            args.add(Timestamp.valueOf(criteria.joinedFrom()));
        }
        if (criteria.joinedToExclusive() != null) {
            clauses.add("created_at < ?");
            args.add(Timestamp.valueOf(criteria.joinedToExclusive()));
        }

        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static java.time.LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
