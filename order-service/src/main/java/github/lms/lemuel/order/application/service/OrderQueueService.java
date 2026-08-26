package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.ViewOrderQueuesUseCase;
import github.lms.lemuel.order.application.port.out.LoadOrderQueuePort;
import github.lms.lemuel.order.application.port.out.LoadOrderQueuePort.StatusWaiting;
import github.lms.lemuel.order.domain.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 작업 큐 서비스 — 상태를 <b>운영자가 해야 할 일</b> 단위로 묶고 기한을 매긴다.
 *
 * <p>큐 정의를 여기 둔 이유: "어느 상태가 한 칸인가"는 업무 규칙이지 SQL 도 화면도 아니다.
 * 어댑터는 상태별 한 줄만 돌려주고, 화면은 받은 목록을 그리기만 한다.
 */
@Service
public class OrderQueueService implements ViewOrderQueuesUseCase {

    /**
     * 큐 정의. <b>순서가 화면 순서</b>다 — 돈이 묶여 있고 되돌리기 어려운 것부터 위로 둔다.
     *
     * <p>SLA 는 "이 시간을 넘기면 고객이 먼저 묻는다"를 기준으로 잡았다. 배송 장기 체류만
     * 7일인 이유는 그 시계가 우리 손이 아니라 택배사 쪽에서 돌기 때문이다 — 하루 넘었다고
     * 운영자가 할 수 있는 일이 없으면 그 알림은 무시하는 법만 가르친다.
     */
    static final List<QueueDef> QUEUES = List.of(
            new QueueDef("REFUND_REQUESTED", "환불 신청", 24,
                    List.of(OrderStatus.REFUND_REQUESTED), false),
            new QueueDef("CANCELLATION_REQUESTED", "취소 신청", 24,
                    List.of(OrderStatus.CANCELLATION_REQUESTED), false),
            // 교환은 환불보다 아래에 둔다 — 돈이 묶이는 대신 물건이 묶이고, 되돌리는 비용이 낮다.
            // 다만 회수·재배송 두 번의 배송이 남아 있어 방치하면 체감 지연이 가장 길어진다.
            new QueueDef("EXCHANGE_REQUESTED", "교환 신청", 24,
                    List.of(OrderStatus.EXCHANGE_REQUESTED), false),
            // 승인은 났는데 종단(CANCELED)까지 못 간 주문. 고객 화면에는 "취소 승인"이 떠 있고
            // 실제로는 아무것도 돌려받지 못한 상태라, 신청 대기보다 오히려 나쁘다.
            new QueueDef("CANCELLATION_APPROVED", "취소 승인 후 미완료", 24,
                    List.of(OrderStatus.CANCELLATION_APPROVED), false),
            new QueueDef("AWAITING_SHIPMENT", "발송 대기", 48,
                    List.of(OrderStatus.PAID, OrderStatus.SHIPPING_PENDING), false),
            new QueueDef("IN_TRANSIT_STALE", "배송 장기 체류", 24 * 7,
                    List.of(OrderStatus.IN_TRANSIT), false),
            // CREATED 는 주문이 태어난 상태라 이력 행이 아예 없는 것이 정상이고, 그때
            // created_at 은 대체값이 아니라 <b>정확히</b> 그 상태가 된 시각이다.
            new QueueDef("UNPAID", "미결제", 24,
                    List.of(OrderStatus.CREATED), true));

    static {
        // 한 상태가 두 큐에 들어가면 총합이 실제보다 커진다. 정의를 고치다 흔히 나는 실수라
        // 기동 시점에 죽인다 — 조용히 두면 "밀린 일 40건"이 실제 32건인 화면이 된다.
        Set<OrderStatus> seen = new HashSet<>();
        for (QueueDef def : QUEUES) {
            for (OrderStatus status : def.statuses()) {
                if (!seen.add(status)) {
                    throw new IllegalStateException("작업 큐 정의가 상태를 중복 포함합니다: " + status);
                }
            }
        }
    }

    private final LoadOrderQueuePort queuePort;
    private final Clock clock;

    public OrderQueueService(LoadOrderQueuePort queuePort, Clock clock) {
        this.queuePort = queuePort;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderQueues view() {
        LocalDateTime asOf = LocalDateTime.now(clock);

        Map<String, LocalDateTime> deadlines = new LinkedHashMap<>();
        for (QueueDef def : QUEUES) {
            LocalDateTime deadline = asOf.minusHours(def.slaHours());
            for (OrderStatus status : def.statuses()) {
                deadlines.put(status.name(), deadline);
            }
        }

        Map<String, StatusWaiting> byStatus = new HashMap<>();
        for (StatusWaiting row : queuePort.waitingByStatus(deadlines)) {
            byStatus.put(row.status(), row);
        }

        List<QueueBucket> buckets = new ArrayList<>(QUEUES.size());
        for (QueueDef def : QUEUES) {
            buckets.add(toBucket(def, byStatus, asOf));
        }
        return new OrderQueues(asOf, List.copyOf(buckets));
    }

    /**
     * 큐 한 칸을 만든다.
     *
     * <p>여러 상태를 묶은 큐(발송 대기)는 건수·초과 건수는 더하고 <b>가장 오래된 시각은 더 이른
     * 쪽</b>을 고른다. 더하면 안 되는 값을 더하는 실수를 막으려고 합치는 자리를 하나로 모았다.
     */
    private QueueBucket toBucket(QueueDef def, Map<String, StatusWaiting> byStatus, LocalDateTime asOf) {
        long count = 0;
        long overdue = 0;
        long withoutHistory = 0;
        LocalDateTime oldest = null;

        for (OrderStatus status : def.statuses()) {
            StatusWaiting row = byStatus.get(status.name());
            if (row == null) {
                continue;   // 그 상태의 주문이 0 건 — 어댑터는 행 자체를 안 준다
            }
            count += row.count();
            overdue += row.overdueCount();
            withoutHistory += row.withoutHistoryCount();
            if (row.oldestWaitingSince() != null
                    && (oldest == null || row.oldestWaitingSince().isBefore(oldest))) {
                oldest = row.oldestWaitingSince();
            }
        }

        Long waitingHours = oldest == null ? null : ChronoUnit.HOURS.between(oldest, asOf);
        return new QueueBucket(
                def.key(),
                def.label(),
                def.statuses().stream().map(Enum::name).toList(),
                count,
                oldest,
                waitingHours,
                def.slaHours(),
                overdue,
                // 이력 없이 주문 일시로 잰 것이 "정확"한 큐에서는 0 으로 내보낸다. 그러지 않으면
                // 미결제 큐가 언제나 "전 건 추정"으로 표시되고, 그 표시가 늘 켜져 있으면 정작
                // 값이 의심스러운 큐에서 켜졌을 때 아무도 보지 않는다.
                def.ageFromCreationIsExact() ? 0 : withoutHistory);
    }

    /**
     * 큐 정의 한 줄.
     *
     * @param ageFromCreationIsExact 이력이 없을 때 {@code created_at} 으로 재는 것이 대체가 아니라
     *                               정확한 값인가. 주문이 태어난 상태(CREATED)만 참이다
     */
    record QueueDef(String key, String label, int slaHours, List<OrderStatus> statuses,
                    boolean ageFromCreationIsExact) {
    }
}
