package github.lms.lemuel.expirynotice.adapter.out.persistence;

import github.lms.lemuel.expirynotice.application.port.out.LoadExpiringItemsPort;
import github.lms.lemuel.expirynotice.domain.ExpiringItem;
import github.lms.lemuel.expirynotice.domain.ExpirySubject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 만료 임박 대상 조회 — 세 애그리것을 <b>읽기만</b> 한다.
 *
 * <p>JPA 리포지토리를 늘리지 않고 JDBC 로 간 이유: 이 조회는 세 애그리것을 가로지르고, 필요한 것은
 * 엔티티가 아니라 통보용 다섯 칸뿐이다. 엔티티로 끌어오면 영속성 컨텍스트에 금전 애그리것 수만 건이
 * 올라가고, 통보 배치가 실수로 그걸 수정하면 dirty checking 이 조용히 UPDATE 를 날린다.
 * 읽기 전용이라는 사실을 타입으로 못박는 편이 안전하다.
 *
 * <p>세 쿼리는 조건이 같다 — <b>아직 살아 있고, 남은 금액이 있고, 만료 시각이 창 안</b>. 다른 것은
 * 어느 칸이 사용자이고 어느 칸이 금액이냐뿐이다.
 */
@Component
public class ExpiringItemJdbcAdapter implements LoadExpiringItemsPort {

    /**
     * 포인트 로트 — 계정을 거쳐야 사용자에 닿는다.
     * {@code remaining_amount > 0} 이 없으면 이미 다 쓴 로트까지 "곧 사라진다" 고 알리게 된다.
     */
    private static final String POINT_LOT_SQL = """
            SELECT l.id, a.user_id, l.remaining_amount, l.expires_at
              FROM point_lots l
              JOIN point_accounts a ON a.id = l.account_id
             WHERE l.status = 'ACTIVE'
               AND l.remaining_amount > 0
               AND l.expires_at IS NOT NULL
               AND l.expires_at >= ? AND l.expires_at < ?
             ORDER BY l.expires_at ASC, l.id ASC
             LIMIT ?
            """;

    /**
     * 기프트카드 — <b>등록된 카드만</b> 대상이다.
     *
     * <p>{@code owner_user_id} 는 REGISTERED 이전에 NULL 이고 스키마 제약이 그렇게 강제한다.
     * 미등록 카드는 시스템이 주인을 모르므로 통보할 수 없다. 조건을 빼면 {@code user_id} 가 NULL 인
     * 통보가 대량으로 원장에 쌓이고, 받는 사람 없는 이벤트가 그대로 발행된다.
     */
    private static final String GIFT_CARD_SQL = """
            SELECT g.id, g.owner_user_id, g.remaining_amount, g.expires_at
              FROM gift_cards g
             WHERE g.status = 'REGISTERED'
               AND g.owner_user_id IS NOT NULL
               AND g.remaining_amount > 0
               AND g.expires_at >= ? AND g.expires_at < ?
             ORDER BY g.expires_at ASC, g.id ASC
             LIMIT ?
            """;

    /**
     * 선물 수령권 — 아직 안 찾아간 것만.
     *
     * <p>사용자 칸에 <b>보낸 사람</b>이 들어간다. 받는 사람은 회원이 아닐 수 있어 전화번호밖에 없고
     * ({@code recipient_phone}), 만료 전에 손을 쓸 수 있는 쪽은 보낸 사람이다.
     *
     * <p>PENDING 뿐 아니라 VERIFIED 도 포함한다 — 본인확인만 하고 수령을 안 끝낸 상태도 그대로 만료된다.
     * 여기서 VERIFIED 를 빼면 "거의 다 온" 사람들만 통보를 못 받는다.
     *
     * <p>{@code expires_at} 이 이 표만 {@code TIMESTAMP}(무 TZ)다. 매핑에서 UTC 로 읽는다 — 아래 참고.
     */
    private static final String GIFT_CLAIM_SQL = """
            SELECT c.id, c.sender_user_id, c.expires_at, c.recipient_phone
              FROM order_gift_claims c
             WHERE c.status IN ('PENDING', 'VERIFIED')
               AND c.expires_at >= ? AND c.expires_at < ?
             ORDER BY c.expires_at ASC, c.id ASC
             LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public ExpiringItemJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ExpiringItem> findExpiringBetween(ExpirySubject subject,
                                                  OffsetDateTime floorInclusive,
                                                  OffsetDateTime ceilingExclusive,
                                                  int limit) {
        Timestamp floor = Timestamp.from(floorInclusive.toInstant());
        Timestamp ceiling = Timestamp.from(ceilingExclusive.toInstant());
        return switch (subject) {
            case POINT_LOT -> jdbcTemplate.query(POINT_LOT_SQL, amountMapper(ExpirySubject.POINT_LOT),
                    floor, ceiling, limit);
            case GIFT_CARD -> jdbcTemplate.query(GIFT_CARD_SQL, amountMapper(ExpirySubject.GIFT_CARD),
                    floor, ceiling, limit);
            case GIFT_CLAIM -> jdbcTemplate.query(GIFT_CLAIM_SQL, giftClaimMapper(), floor, ceiling, limit);
        };
    }

    /** 금액이 있는 대상(포인트·기프트카드) 공통 매핑 — 칸 순서가 같아 하나로 쓴다. */
    private static RowMapper<ExpiringItem> amountMapper(ExpirySubject subject) {
        return (ResultSet rs, int rowNum) -> new ExpiringItem(
                subject,
                rs.getLong(1),
                userIdOrNull(rs, 2),
                rs.getBigDecimal(3),
                offsetDateTime(rs, 4),
                null);
    }

    private static RowMapper<ExpiringItem> giftClaimMapper() {
        return (ResultSet rs, int rowNum) -> new ExpiringItem(
                ExpirySubject.GIFT_CLAIM,
                rs.getLong(1),
                userIdOrNull(rs, 2),
                // 수령권 자체엔 금액 칸이 없다. 금액을 0 으로 채우면 "0원짜리가 만료된다" 는 거짓이 되므로 null 이다.
                null,
                offsetDateTime(rs, 3),
                rs.getString(4));
    }

    private static Long userIdOrNull(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * TIMESTAMPTZ 든 TIMESTAMP 든 같은 방식으로 읽는다.
     *
     * <p>{@code order_gift_claims.expires_at} 만 TZ 가 없다. JDBC 드라이버는 무 TZ 칸을 JVM 기본
     * 시간대로 해석하므로, 여기서 {@link Timestamp#toInstant()} 를 거쳐 절대시각으로 고정한다 —
     * 컨테이너 TZ 가 바뀌어도 창 경계가 흔들리지 않는다.
     */
    private static OffsetDateTime offsetDateTime(ResultSet rs, int column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
