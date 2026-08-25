package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.application.port.in.ViewRevenueStatisticsUseCase.TenderRevenue;
import github.lms.lemuel.payment.application.port.out.LoadRevenueStatisticsPort;
import github.lms.lemuel.payment.domain.TenderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 기간 매출 집계 어댑터.
 *
 * <h2>집계를 DB 에서 끝내는 이유</h2>
 * 행을 끌어와 애플리케이션에서 더하면 기간이 길어질수록 그대로 느려지고, 언젠가 "조회가 좀
 * 느리다"로 시작해 타임아웃으로 끝난다. 합계는 DB 가 하고 여기서는 날짜별 한 줄씩만 받는다.
 *
 * <h2>스키마를 손으로 한정하는 이유</h2>
 * 네이티브 SQL 은 hibernate {@code default_schema}(opslab) 의 적용을 받지 않는다
 * ({@code PaymentOwnerJdbcAdapter} 와 동일 관례).
 */
@Component
public class JdbcRevenueStatisticsAdapter implements LoadRevenueStatisticsPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcRevenueStatisticsAdapter.class);

    /**
     * 일자별 수납.
     *
     * <p>시간축이 {@code captured_at} 인 것이 이 통계의 전부다. 주문의 <b>현재</b> 상태로 세면
     * 발송된 주문이 매출에서 빠지고, 결제의 {@code updated_at} 으로 세면 환불된 건의 판매일이
     * 환불일로 옮겨간다. 실제로 돈이 잡힌 시각은 하나뿐이다.
     *
     * <p>상태를 {@code CAPTURED, REFUNDED} 로 한정한다. 뒤에 환불이 됐어도 <b>수납은 그날
     * 일어난 사실</b>이라 총 수납액에 남아야 하고, 차감은 환불 계열이 따로 한다.
     */
    private static final String CAPTURES_BY_DAY = """
            SELECT CAST(p.captured_at AS DATE) AS d,
                   COUNT(*)                    AS cnt,
                   COALESCE(SUM(p.amount), 0)  AS amt
              FROM opslab.payments p
             WHERE p.captured_at >= ?
               AND p.captured_at <  ?
               AND p.status IN ('CAPTURED', 'REFUNDED')
             GROUP BY 1
             ORDER BY 1
            """;

    /**
     * 일자별 환불 완료.
     *
     * <p>{@code REQUESTED} 는 아직 돈이 나가지 않았고 {@code FAILED} 는 나가지 않은 채 끝났다.
     * 신청 시각({@code requested_at})이 아니라 완료 시각으로 다는 이유도 같다 — 신청만 하고
     * 실패한 건까지 차감하면 있지도 않은 환불이 매출을 깎는다.
     */
    private static final String REFUNDS_BY_DAY = """
            SELECT CAST(r.completed_at AS DATE) AS d,
                   COUNT(*)                     AS cnt,
                   COALESCE(SUM(r.amount), 0)   AS amt
              FROM opslab.refunds r
             WHERE r.completed_at >= ?
               AND r.completed_at <  ?
               AND r.status = 'COMPLETED'
             GROUP BY 1
             ORDER BY 1
            """;

    /**
     * 결제수단별 구성.
     *
     * <p>기간 판정은 <b>tender 가 아니라 결제</b>의 {@code captured_at} 으로 한다. tender 행에는
     * 수납 시각이 없고 {@code updated_at} 은 환불 때도 움직이므로, tender 시각으로 자르면 같은
     * 결제의 카드 라인과 포인트 라인이 서로 다른 달에 설 수 있다.
     *
     * <p>tender 상태도 함께 거른다. 분할결제는 한 라인이 실패해도 다른 라인이 성립할 수 있어,
     * 결제가 캡처됐다는 것이 모든 라인이 캡처됐다는 뜻은 아니다.
     */
    private static final String CAPTURED_BY_TENDER = """
            SELECT t.tender_type              AS tender_type,
                   COUNT(*)                   AS cnt,
                   COALESCE(SUM(t.amount), 0) AS amt
              FROM opslab.payment_tenders t
              JOIN opslab.payments p ON p.id = t.payment_id
             WHERE p.captured_at >= ?
               AND p.captured_at <  ?
               AND p.status IN ('CAPTURED', 'REFUNDED')
               AND t.status IN ('CAPTURED', 'REFUNDED')
             GROUP BY t.tender_type
             ORDER BY amt DESC, tender_type ASC
            """;

    private static final RowMapper<DailyAmount> DAILY_MAPPER = (rs, rowNum) -> new DailyAmount(
            rs.getDate("d").toLocalDate(), rs.getLong("cnt"), rs.getBigDecimal("amt"));

    /**
     * 모르는 {@code tender_type} 은 예외가 아니라 <b>건너뛴다</b>.
     *
     * <p>수단을 enum 에서 빼는 것은 정상적인 변경인데, 그 순간 이미 쌓인 옛 행 때문에 매출
     * 화면 전체가 500 으로 죽으면 수단 하나를 지운 대가로 보고서를 잃는다. 건너뛴 금액은
     * 총액과의 차이로 "수단 미상" 칸에 그대로 드러난다.
     */
    private static final RowMapper<TenderRevenue> TENDER_MAPPER = (rs, rowNum) -> {
        String raw = rs.getString("tender_type");
        TenderType type;
        try {
            type = TenderType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("알 수 없는 결제수단 — 건너뜀: {}", raw);
            return null;
        }
        return new TenderRevenue(type, type.usesExternalPg(), rs.getLong("cnt"), rs.getBigDecimal("amt"));
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcRevenueStatisticsAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<DailyAmount> capturesByDay(LocalDateTime from, LocalDateTime toExclusive) {
        return jdbcTemplate.query(CAPTURES_BY_DAY, DAILY_MAPPER,
                Timestamp.valueOf(from), Timestamp.valueOf(toExclusive));
    }

    @Override
    public List<DailyAmount> refundsByDay(LocalDateTime from, LocalDateTime toExclusive) {
        return jdbcTemplate.query(REFUNDS_BY_DAY, DAILY_MAPPER,
                Timestamp.valueOf(from), Timestamp.valueOf(toExclusive));
    }

    @Override
    public List<TenderRevenue> capturedByTender(LocalDateTime from, LocalDateTime toExclusive) {
        return jdbcTemplate.query(CAPTURED_BY_TENDER, TENDER_MAPPER,
                        Timestamp.valueOf(from), Timestamp.valueOf(toExclusive)).stream()
                .filter(Objects::nonNull)
                .toList();
    }
}
