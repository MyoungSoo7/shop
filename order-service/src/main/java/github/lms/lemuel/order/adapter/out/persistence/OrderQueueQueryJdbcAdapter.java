package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.application.port.out.LoadOrderQueuePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 작업 큐 집계 — 대기 시간을 <b>주문 일시가 아니라 상태 변경 이력</b>에서 잰다.
 *
 * <h2>{@code orders.created_at} 을 쓰면 안 되는 이유</h2>
 * {@code orders} 에는 상태가 <b>언제</b> 바뀌었는지가 없다. {@code updated_at} 은 배송지 수정 같은
 * 무관한 변경에도 움직이고, {@code created_at} 은 주문이 들어온 시각이다. 석 달 전 주문이 오늘
 * 취소 신청되면 {@code created_at} 기준으로는 <b>90일째 밀린 취소 신청</b>이 된다. 그 화면을 보고
 * 운영자가 할 수 있는 판단은 없다.
 *
 * <p>그래서 {@code opslab.order_status_history} 에서 <b>현재 상태로 진입한 마지막 시각</b>을 찾는다
 * (V50). 이 표는 {@code ChangeOrderStatusService} · {@code CancelOrderItemsService} ·
 * {@code WithdrawOrderRequestService} 가 전이할 때마다 쓰고, 결제 확정(CREATED→PAID)도
 * payment 컨텍스트가 {@code updateStatus} 를 거치므로 함께 남는다.
 *
 * <h2>같은 상태로 두 번 기록된 이력</h2>
 * 이력 행이 <b>상태가 바뀔 때만</b> 쌓이는 것이 아니다. 라인 단위 부분 취소
 * ({@code CancelOrderItemsService})는 주문 상태를 그대로 둔 채 이력을 남기므로
 * {@code previous_status = new_status} 인 행이 생긴다. 그 행까지 "이 상태가 된 시각"으로
 * 세면 <b>8월 5일부터 발송 대기 중이던 주문이 8월 24일 부분 취소 한 번에 갓 들어온 주문으로
 * 바뀐다</b> — 가장 오래 밀린 건이 목록에서 사라지는 방향이라 화면상 아무 이상이 없다.
 * 그래서 {@code previous_status IS DISTINCT FROM new_status} 로 <b>실제 전이</b>만 본다
 * ({@code IS DISTINCT FROM} 인 이유는 최초 전이의 {@code previous_status} 가 NULL 이기 때문이다).
 *
 * <h2>이력이 없는 주문</h2>
 * V50 이전에 만들어진 주문과 시드 데이터에는 이력 행이 없다. 이때만 {@code created_at} 으로
 * 대신 재되, <b>그 건수를 따로 세어 올려보낸다</b>({@code withoutHistoryCount}).
 * 조용히 대체하면 이력 없는 옛 주문 한 건이 "가장 오래 밀린 일" 자리를 영구히 차지하면서
 * 그 사실은 화면 어디에도 나타나지 않는다.
 *
 * <h2>기한이 상태마다 다른 것을 SQL 안에서 처리하는 이유</h2>
 * 큐마다 SLA 가 다르다(미결제 24시간 vs 배송 장기 체류 7일). 초과 <b>건수</b>는 전체 행을 봐야
 * 나오므로 자바로 가져와 셀 수 없다 — 밀린 주문 전부를 메모리로 올리는 순간 큐 화면이
 * 가장 무거운 질의가 된다. {@code sla} CTE 로 상태별 기한을 같이 넘겨 한 번에 센다.
 */
@Repository
public class OrderQueueQueryJdbcAdapter implements LoadOrderQueuePort {

    /**
     * {@code %s} 자리에는 {@code (?, ?)} 쌍만 들어간다 — 사용자 입력이 문자열로 섞이는 자리가 없다.
     *
     * <p>{@code LEFT JOIN LATERAL} 인 이유: 이력이 없는 주문도 <b>남아야</b> 한다. {@code JOIN} 이면
     * 그 주문들이 큐에서 통째로 사라져 밀린 일이 실제보다 적게 보인다 — 없는 일이 있는 것처럼
     * 보이는 것보다 나쁘다.
     */
    private static final String WAITING_SQL = """
            WITH sla(status, deadline) AS (
                VALUES %s
            ),
            waiting AS (
                SELECT o.status                                  AS status,
                       COALESCE(h.changed_at, o.created_at)      AS waiting_since,
                       (h.changed_at IS NULL)                    AS no_history,
                       sla.deadline                              AS deadline
                  FROM opslab.orders o
                  JOIN sla ON sla.status = o.status
                  LEFT JOIN LATERAL (
                       SELECT sh.changed_at
                         FROM opslab.order_status_history sh
                        WHERE sh.order_id = o.id
                          AND sh.new_status = o.status
                          AND sh.previous_status IS DISTINCT FROM sh.new_status
                        ORDER BY sh.changed_at DESC, sh.id DESC
                        LIMIT 1
                  ) h ON TRUE
            )
            SELECT status,
                   COUNT(*)                                        AS bucket_count,
                   MIN(waiting_since)                              AS oldest_waiting_since,
                   COUNT(*) FILTER (WHERE waiting_since < deadline) AS overdue_count,
                   COUNT(*) FILTER (WHERE no_history)               AS without_history_count
              FROM waiting
             GROUP BY status
            """;

    /** 첫 쌍에만 캐스팅을 단다. 없으면 PG 가 파라미터 타입을 {@code bytea} 로 추론해 42P18 로 죽는다. */
    private static final String FIRST_PAIR = "(CAST(? AS varchar), CAST(? AS timestamp))";
    private static final String NEXT_PAIR = "(?, ?)";

    private static final RowMapper<StatusWaiting> MAPPER = (rs, rowNum) -> {
        Timestamp oldest = rs.getTimestamp("oldest_waiting_since");
        return new StatusWaiting(
                rs.getString("status"),
                rs.getLong("bucket_count"),
                oldest == null ? null : oldest.toLocalDateTime(),
                rs.getLong("overdue_count"),
                rs.getLong("without_history_count"));
    };

    private final JdbcTemplate jdbcTemplate;

    public OrderQueueQueryJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<StatusWaiting> waitingByStatus(Map<String, LocalDateTime> deadlineByStatus) {
        if (deadlineByStatus == null || deadlineByStatus.isEmpty()) {
            return List.of();
        }
        // 상태 이름으로 정렬해 SQL 문자열을 고정한다 — 호출마다 순서가 달라지면 같은 질의가
        // 서로 다른 실행 계획 캐시 항목이 된다.
        Map<String, LocalDateTime> ordered = new TreeMap<>(deadlineByStatus);

        StringBuilder pairs = new StringBuilder();
        List<Object> params = new ArrayList<>(ordered.size() * 2);
        for (Map.Entry<String, LocalDateTime> entry : ordered.entrySet()) {
            pairs.append(pairs.isEmpty() ? FIRST_PAIR : ", " + NEXT_PAIR);
            params.add(entry.getKey());
            params.add(Timestamp.valueOf(entry.getValue()));
        }

        return jdbcTemplate.query(WAITING_SQL.formatted(pairs), MAPPER, params.toArray());
    }
}
