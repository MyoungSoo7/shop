package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.application.port.in.SearchOperatorsUseCase.OperatorSummary;
import github.lms.lemuel.user.application.port.out.SearchOperatorsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 운영자 계정 콘솔 조회 어댑터 — 목록·집계라 JPA 를 거치지 않고 SQL 로 직접 읽는다.
 *
 * <p>규약은 {@link MemberConsoleQueryJdbcAdapter} 와 같다.
 * <ul>
 *   <li><b>{@code opslab.} 한정</b> — Hibernate 의 {@code default_schema} 는 JPA 경로에만 적용되고
 *       JdbcTemplate 은 커넥션의 {@code search_path} 를 따른다. 한정하지 않으면 컴파일도 단위
 *       테스트도 통과한 채 배포 후에 "relation does not exist" 로 터진다.</li>
 *   <li><b>동적 조건을 문자열로 조립</b> — {@code (? IS NULL OR col = ?)} 관용구는 PostgreSQL 에서
 *       파라미터 타입 추론이 {@code bytea} 로 떨어져 실행 시점에 터진다. 값이 있는 조건만 붙이고
 *       그 조건에만 바인딩한다. 조립되는 것은 상수 조각뿐이고 사용자 입력은 전부 바인딩이다.</li>
 * </ul>
 *
 * <p><b>정렬이 다른 이유</b>: 회원 콘솔은 가입 최신순이지만 여기는 {@code last_login_at ASC NULLS
 * FIRST} 다. 이 화면을 여는 이유가 "가장 오래 방치된 권한 계정"을 찾는 것이라, 한 번도 로그인한
 * 적 없는 계정이 맨 위여야 한다. 같은 값이 겹칠 때를 대비해 {@code id ASC} 를 붙인다 — 정렬이
 * 흔들리면 페이지를 넘길 때 같은 계정이 두 번 보이거나 사라진다.
 *
 * <p><b>잠김 여부를 SQL 에서 판정하는 이유</b>: 목록의 표시와 건수가 같은 기준 시각을 봐야 한다.
 * 서비스가 넘긴 {@code now} 하나를 SELECT 와 WHERE 양쪽에 같이 바인딩한다.
 */
@Repository
@RequiredArgsConstructor
public class OperatorConsoleQueryJdbcAdapter implements SearchOperatorsPort {

    private static final RowMapper<OperatorSummary> ROW_MAPPER = (rs, rowNum) -> new OperatorSummary(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("name"),
            rs.getString("role"),
            rs.getBoolean("is_active"),
            toLocalDateTime(rs.getTimestamp("last_login_at")),
            rs.getInt("failed_login_attempts"),
            toLocalDateTime(rs.getTimestamp("locked_until")),
            rs.getBoolean("is_locked"),
            toLocalDateTime(rs.getTimestamp("password_changed_at")),
            toLocalDateTime(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<OperatorSummary> search(OperatorCriteria criteria, int page, int size) {
        List<Object> args = new ArrayList<>();
        // SELECT 절의 바인딩이 WHERE 절보다 먼저 온다 — 순서가 곧 ? 의 위치다.
        args.add(Timestamp.valueOf(criteria.now()));
        String where = buildWhere(criteria, args);
        args.add(size);
        args.add(page * size);

        return jdbcTemplate.query("""
                SELECT id, email, name, role, is_active, last_login_at, failed_login_attempts,
                       locked_until, password_changed_at, created_at,
                       (locked_until IS NOT NULL AND locked_until > ?) AS is_locked
                FROM opslab.users
                """ + where + """
                 ORDER BY last_login_at ASC NULLS FIRST, id ASC
                 LIMIT ? OFFSET ?
                """, ROW_MAPPER, args.toArray());
    }

    @Override
    public long count(OperatorCriteria criteria) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM opslab.users " + where, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /** 값이 있는 조건만 WHERE 절로 조립하고, 같은 순서로 바인딩 인자를 채운다. */
    private static String buildWhere(OperatorCriteria criteria, List<Object> args) {
        List<String> clauses = new ArrayList<>();

        // 역할 조건은 선택이 아니라 이 콘솔의 정의다. 비면 전 회원 조회가 되므로 막는다 —
        // 조건이 조용히 빠져 넓게 열리는 것이 이 저장소에서 네 번 난 사고의 형태다.
        List<String> roles = criteria.roles();
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("운영자 조회는 역할 조건 없이 실행할 수 없습니다");
        }
        clauses.add("role IN (" + String.join(", ", Collections.nCopies(roles.size(), "?")) + ")");
        args.addAll(roles);

        if (criteria.keyword() != null) {
            clauses.add("(email ILIKE ? OR name ILIKE ?)");
            String pattern = "%" + criteria.keyword() + "%";
            args.add(pattern);
            args.add(pattern);
        }
        if (criteria.lockedOnly()) {
            clauses.add("(locked_until IS NOT NULL AND locked_until > ?)");
            args.add(Timestamp.valueOf(criteria.now()));
        }
        if (criteria.neverLoggedIn()) {
            clauses.add("last_login_at IS NULL");
        } else if (criteria.idleBefore() != null) {
            // 미사용 조건은 "한 번도 안 씀"을 포함한다 — 방치된 권한 계정을 찾는 질문에
            // 로그인 이력이 아예 없는 계정이 빠지면 가장 위험한 쪽이 결과에서 사라진다.
            clauses.add("(last_login_at IS NULL OR last_login_at < ?)");
            args.add(Timestamp.valueOf(criteria.idleBefore()));
        }

        return " WHERE " + String.join(" AND ", clauses);
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
