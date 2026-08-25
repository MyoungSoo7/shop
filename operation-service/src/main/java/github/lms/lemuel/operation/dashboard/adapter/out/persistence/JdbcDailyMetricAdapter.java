package github.lms.lemuel.operation.dashboard.adapter.out.persistence;

import github.lms.lemuel.operation.dashboard.application.port.out.LoadDailyMetricPort;
import github.lms.lemuel.operation.dashboard.application.port.out.UpsertDailyMetricPort;
import github.lms.lemuel.operation.dashboard.domain.DailyMetric;
import github.lms.lemuel.operation.dashboard.domain.DashboardMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * {@code ops_daily_metric} 어댑터.
 *
 * <h2>누적이 한 문장인 이유</h2>
 * "읽어서 더한 뒤 쓴다" 로 구현하면 같은 지표의 두 이벤트가 동시에 도착할 때 한쪽이 덮여
 * 사라진다. 파티션이 여럿이거나 레플리카가 둘이면 흔한 일이고, 결과는 <b>조금 작은 매출</b>이라
 * 아무도 이상하게 여기지 않는다. {@code ON CONFLICT DO UPDATE} 는 그 계산을 DB 안에서
 * 원자적으로 끝낸다.
 *
 * <h2>스키마를 손으로 한정하는 이유</h2>
 * 네이티브 SQL 은 hibernate {@code default_schema}(opslab) 의 적용을 받지 않는다
 * (JdbcNotificationJournal·SpringDataMetricBucketRepository 와 동일 관례).
 */
@Component
public class JdbcDailyMetricAdapter implements UpsertDailyMetricPort, LoadDailyMetricPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcDailyMetricAdapter.class);

    private static final String UPSERT = """
            INSERT INTO opslab.ops_daily_metric
                (metric_date, metric_key, event_count, amount_sum, amount_unknown_count, updated_at)
            VALUES (?, ?, 1, ?, ?, NOW())
            ON CONFLICT (metric_date, metric_key) DO UPDATE
            SET event_count          = opslab.ops_daily_metric.event_count + 1,
                amount_sum           = opslab.ops_daily_metric.amount_sum + EXCLUDED.amount_sum,
                amount_unknown_count = opslab.ops_daily_metric.amount_unknown_count
                                       + EXCLUDED.amount_unknown_count,
                updated_at           = NOW()
            """;

    private static final String SELECT_BY_DATE = """
            SELECT metric_date, metric_key, event_count, amount_sum, amount_unknown_count, updated_at
              FROM opslab.ops_daily_metric
             WHERE metric_date = ?
            """;

    /**
     * 모르는 {@code metric_key} 는 예외가 아니라 <b>건너뛴다</b>.
     *
     * <p>지표를 enum 에서 빼는 것은 정상적인 변경인데, 그 순간 이미 쌓인 옛 행 때문에 화면
     * 전체가 500 으로 죽으면 지표 하나를 지운 대가로 대시보드를 잃는다. 남은 행은 로그로만
     * 알리고 그대로 둔다.
     */
    private static final RowMapper<DailyMetric> ROW_MAPPER = (rs, rowNum) -> {
        String key = rs.getString("metric_key");
        DashboardMetric metric;
        try {
            metric = DashboardMetric.valueOf(key);
        } catch (IllegalArgumentException e) {
            log.warn("알 수 없는 지표 키 — 건너뜀: {}", key);
            return null;
        }
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new DailyMetric(
                rs.getDate("metric_date").toLocalDate(),
                metric,
                rs.getLong("event_count"),
                rs.getBigDecimal("amount_sum"),
                rs.getLong("amount_unknown_count"),
                updatedAt == null ? null : updatedAt.toInstant());
    };

    private final JdbcTemplate jdbc;

    public JdbcDailyMetricAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void accumulate(LocalDate date, DashboardMetric metric, BigDecimal amount) {
        // amount 가 null 이면 합계에는 0 을 더하고 "미상" 카운터만 올린다 — 모르는 값을 0 으로
        // 세는 것과 0원으로 세는 것은 다르다. 전자는 화면이 말할 수 있고 후자는 조용히 틀린다.
        jdbc.update(UPSERT,
                Date.valueOf(date),
                metric.name(),
                amount == null ? BigDecimal.ZERO : amount,
                amount == null ? 1L : 0L);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyMetric> findByDate(LocalDate date) {
        return jdbc.query(SELECT_BY_DATE, ROW_MAPPER, Date.valueOf(date)).stream()
                .filter(Objects::nonNull)
                .toList();
    }
}
