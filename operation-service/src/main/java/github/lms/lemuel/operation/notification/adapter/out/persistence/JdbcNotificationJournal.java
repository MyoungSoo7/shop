package github.lms.lemuel.operation.notification.adapter.out.persistence;

import github.lms.lemuel.operation.notification.application.ChannelResult;
import github.lms.lemuel.operation.notification.application.DispatchRecord;
import github.lms.lemuel.operation.notification.application.port.out.NotificationJournal;
import github.lms.lemuel.operation.notification.application.port.out.NotificationJournalQuery;
import github.lms.lemuel.operation.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 발송 저널의 PostgreSQL 어댑터 — 멱등(L2)과 이력을 같은 테이블에 쓴다.
 *
 * <h2>멱등이 코드가 아니라 인덱스인 이유</h2>
 * {@code INSERT … ON CONFLICT (event_id) DO NOTHING RETURNING id} 는 <b>한 문장 안에서</b> 중복을
 * 판정하고 자리를 잡는다. "SELECT 로 있는지 보고 없으면 INSERT" 로 쓰면 두 레플리카가 같은 찰나에
 * 둘 다 "없다"를 보고 둘 다 보낸다 — 멱등이 필요한 바로 그 상황에서만 깨지는 종류의 버그다.
 * 반환 행이 있으면 내가 이겼고(=발송 진행), 없으면 남이 이미 가져갔다(=스킵).
 *
 * <h2>저장소 장애 때 발송을 막지 않는다 (fail-open)</h2>
 * DB 가 흔들릴 때 이 어댑터가 예외를 올리면 <b>알림이 통째로 멎는다</b>. 저널이 생기기 전 이 슬라이스는
 * 내구 멱등이 아예 없었으므로, 장애 시 L1(인메모리) 만으로 계속 보내는 것은 <b>이전보다 나쁘지 않다</b>.
 * 반대로 fail-closed 는 "관측을 못 하니 알리지도 않는다"가 되어 명백히 더 나쁘다.
 * 그래서 실패는 warn 으로 남기고 {@link #NO_JOURNAL} 을 돌려 발송을 진행시킨다.
 *
 * <h2>스키마를 손으로 한정하는 이유</h2>
 * 네이티브 SQL 은 hibernate {@code default_schema}(opslab) 의 적용을 받지 않는다
 * (SpringDataMetricBucketRepository·shared-common Outbox 네이티브 쿼리와 동일 관례).
 */
@Component
public class JdbcNotificationJournal implements NotificationJournal, NotificationJournalQuery {

    private static final Logger log = LoggerFactory.getLogger(JdbcNotificationJournal.class);

    /** 저널을 열지 못했을 때의 항목 id — {@link #complete} 가 조용히 무시한다. */
    static final long NO_JOURNAL = -1L;

    /** 조회 상한. 콘솔이 실수로 큰 수를 보내도 한 페이지가 DB 를 통째로 끌어오지 않게 한다. */
    private static final int MAX_LIMIT = 200;

    // 컬럼 폭(마이그레이션과 같은 값). 넘치면 INSERT 가 통째로 실패하는데, 저널 한 줄 때문에
    // 알림 발송을 잃을 이유가 없으므로 자른다.
    private static final int EVENT_ID_MAX = 200;
    private static final int TYPE_MAX = 64;
    private static final int RECIPIENT_MAX = 320;
    private static final int SUBJECT_MAX = 500;
    private static final int CHANNEL_MAX = 40;

    private static final String INSERT_DISPATCH = """
            INSERT INTO opslab.notification_dispatches
                (event_id, type, recipient, subject, body, status)
            VALUES (?, ?, ?, ?, ?, 'PENDING')
            ON CONFLICT (event_id) DO NOTHING
            RETURNING id
            """;

    // resent_from_id 는 발송 후에 붙는다(원본 행을 아는 것은 운영 서비스뿐이다).
    // 이미 계보가 있는 행은 건드리지 않는다 — 재발송의 재발송이 첫 원본을 덮어쓰지 않게.
    private static final String LINK_RESEND = """
            UPDATE opslab.notification_dispatches
               SET resent_from_id = ?
             WHERE event_id = ? AND resent_from_id IS NULL
            """;

    private static final String INSERT_CHANNEL = """
            INSERT INTO opslab.notification_dispatch_channels
                (dispatch_id, channel, status, attempts, error)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (dispatch_id, channel) DO NOTHING
            """;

    private static final String UPDATE_DISPATCH = """
            UPDATE opslab.notification_dispatches
               SET status = ?, channels_total = ?, channels_succeeded = ?, completed_at = NOW()
             WHERE id = ?
            """;

    private static final String SELECT_COLUMNS = """
            SELECT id, event_id, type, recipient, subject, body, status,
                   channels_total, channels_succeeded, resent_from_id, created_at, completed_at
              FROM opslab.notification_dispatches
            """;

    private final JdbcTemplate jdbc;

    public JdbcNotificationJournal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ───────────────────────────── 쓰기 ─────────────────────────────

    @Override
    @Transactional
    public Optional<Long> begin(Notification notification) {
        String eventId = dedupeKeyOf(notification);
        try {
            List<Long> inserted = jdbc.query(INSERT_DISPATCH,
                    (rs, rowNum) -> rs.getLong(1),
                    clip(eventId, EVENT_ID_MAX),
                    clip(typeOf(notification), TYPE_MAX),
                    clip(notification.recipient(), RECIPIENT_MAX),
                    clip(notification.subject(), SUBJECT_MAX),
                    notification.body());
            if (inserted.isEmpty()) {
                return Optional.empty(); // 남이 먼저 가져갔다 = 중복
            }
            return Optional.of(inserted.get(0));
        } catch (DataAccessException e) {
            log.warn("journal begin 실패 — 저널 없이 발송 진행(fail-open) eventId={} cause={}",
                    eventId, e.getMessage());
            return Optional.of(NO_JOURNAL);
        }
    }

    @Override
    @Transactional
    public void complete(long journalId, List<ChannelResult> results) {
        if (journalId == NO_JOURNAL) {
            return; // 애초에 열리지 않은 항목 — 닫을 것이 없다.
        }
        try {
            for (ChannelResult result : results) {
                jdbc.update(INSERT_CHANNEL,
                        journalId,
                        clip(result.channel(), CHANNEL_MAX),
                        result instanceof ChannelResult.Success ? "SUCCESS" : "FAILURE",
                        result.attempts(),
                        result instanceof ChannelResult.Failure failure ? failure.error() : null);
            }
            long succeeded = results.stream().filter(ChannelResult.Success.class::isInstance).count();
            jdbc.update(UPDATE_DISPATCH, statusOf(results.size(), succeeded), results.size(), succeeded, journalId);
        } catch (DataAccessException e) {
            // 이미 발송은 끝났다. 여기서 예외를 올리면 성공한 발송이 실패로 보고된다.
            log.warn("journal complete 실패 — 이력만 유실됨 journalId={} cause={}", journalId, e.getMessage());
        }
    }

    @Override
    public void linkResend(String eventId, long originalId) {
        try {
            jdbc.update(LINK_RESEND, originalId, clip(eventId, EVENT_ID_MAX));
        } catch (DataAccessException e) {
            // 계보는 편의 정보다. 못 붙였다고 이미 나간 재발송을 실패로 보고하지 않는다.
            log.warn("journal linkResend 실패 eventId={} originalId={} cause={}", eventId, originalId, e.getMessage());
        }
    }

    /**
     * 활성 채널 0개는 {@code NO_CHANNEL} 이다 — 배포 설정 오류지 발송 실패가 아니라서
     * 실패 목록에 섞이면 진짜 실패가 묻힌다(디스패처가 같은 구분을 warn 으로 남긴다).
     */
    private static String statusOf(int total, long succeeded) {
        if (total == 0) {
            return "NO_CHANNEL";
        }
        if (succeeded == total) {
            return "DELIVERED";
        }
        return succeeded == 0 ? "FAILED" : "PARTIAL";
    }

    /**
     * 멱등 키. {@code eventId} 가 없는 알림(수기 발송·데모)은 <b>중복 판정 대상이 아니다</b> —
     * 같은 내용을 두 번 보내는 것이 의도인 경로이므로, 매번 새 키를 만들어 이력만 남긴다.
     */
    private static String dedupeKeyOf(Notification notification) {
        String eventId = notification.eventId();
        return (eventId == null || eventId.isBlank()) ? "manual:" + UUID.randomUUID() : eventId;
    }

    private static String typeOf(Notification notification) {
        return notification.type() == null ? "GENERIC" : notification.type().name();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ───────────────────────────── 읽기 ─────────────────────────────

    private static final RowMapper<DispatchRecord> ROW_MAPPER = (rs, rowNum) -> new DispatchRecord(
            rs.getLong("id"),
            rs.getString("event_id"),
            rs.getString("type"),
            rs.getString("recipient"),
            rs.getString("subject"),
            rs.getString("body"),
            rs.getString("status"),
            rs.getInt("channels_total"),
            rs.getInt("channels_succeeded"),
            nullableLong(rs, "resent_from_id"),
            instant(rs, "created_at"),
            instant(rs, "completed_at"),
            List.of());

    @Override
    public List<DispatchRecord> findRecent(String status, String recipient, int limit, int offset) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS);
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, status, recipient);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        args.add(Math.clamp(limit, 1, MAX_LIMIT));
        args.add(Math.max(offset, 0));
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    @Override
    public long count(String status, String recipient) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM opslab.notification_dispatches");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, status, recipient);
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    @Override
    public Optional<DispatchRecord> findById(long id) {
        List<DispatchRecord> found = jdbc.query(SELECT_COLUMNS + " WHERE id = ?", ROW_MAPPER, id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<DispatchRecord.ChannelOutcome> channels = jdbc.query("""
                SELECT channel, status, attempts, error, created_at
                  FROM opslab.notification_dispatch_channels
                 WHERE dispatch_id = ?
                 ORDER BY channel
                """,
                (rs, rowNum) -> new DispatchRecord.ChannelOutcome(
                        rs.getString("channel"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getString("error"),
                        instant(rs, "created_at")),
                id);
        DispatchRecord head = found.get(0);
        return Optional.of(new DispatchRecord(
                head.id(), head.eventId(), head.type(), head.recipient(), head.subject(), head.body(),
                head.status(), head.channelsTotal(), head.channelsSucceeded(), head.resentFromId(),
                head.createdAt(), head.completedAt(), channels));
    }

    /**
     * 필터를 <b>바인딩 파라미터로만</b> 붙인다. 이어 붙이는 것은 코드 상수인 조건절 문자열뿐이고
     * 요청에서 온 값은 {@code ?} 로만 들어간다 — 그래서 아래 동적 SQL 은 주입 경로가 아니다.
     */
    private static void appendFilters(StringBuilder sql, List<Object> args, String status, String recipient) {
        if (status != null && !status.isBlank()) {
            sql.append(args.isEmpty() ? " WHERE" : " AND").append(" status = ?");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        if (recipient != null && !recipient.isBlank()) {
            sql.append(args.isEmpty() ? " WHERE" : " AND").append(" recipient = ?");
            args.add(recipient);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
