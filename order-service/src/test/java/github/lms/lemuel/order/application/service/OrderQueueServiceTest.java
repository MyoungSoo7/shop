package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.ViewOrderQueuesUseCase.OrderQueues;
import github.lms.lemuel.order.application.port.in.ViewOrderQueuesUseCase.QueueBucket;
import github.lms.lemuel.order.application.port.out.LoadOrderQueuePort;
import github.lms.lemuel.order.application.port.out.LoadOrderQueuePort.StatusWaiting;
import github.lms.lemuel.order.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업 큐 서비스 — 상태 묶기와 기한 계산을 본다.
 *
 * <p>어댑터를 가짜로 두는 이유: 여기서 틀릴 수 있는 것은 SQL 이 아니라 <b>합치는 규칙</b>이다.
 * 여러 상태를 한 큐로 묶을 때 건수는 더해야 하고 "가장 오래된 시각"은 더 이른 쪽을 골라야 하는데,
 * 둘 다 long 이라 잘못 더해도 컴파일된다.
 */
class OrderQueueServiceTest {

    /** KST 2026-08-26 09:00. 기한 계산이 전부 이 시각 기준이다. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 9, 0);

    private RecordingPort port;
    private OrderQueueService service;

    @BeforeEach
    void setUp() {
        port = new RecordingPort();
        service = new OrderQueueService(port, FIXED);
    }

    /** 호출 인자를 기록하는 가짜 어댑터. */
    private static class RecordingPort implements LoadOrderQueuePort {
        Map<String, LocalDateTime> requested;
        List<StatusWaiting> rows = new ArrayList<>();

        @Override
        public List<StatusWaiting> waitingByStatus(Map<String, LocalDateTime> deadlineByStatus) {
            this.requested = deadlineByStatus;
            return rows;
        }
    }

    private static QueueBucket bucket(OrderQueues queues, String key) {
        return queues.buckets().stream()
                .filter(b -> b.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("큐가 없다: " + key));
    }

    @Test
    @DisplayName("기준 시각은 KST 다 — UTC 로 재면 기한 초과 판정이 아홉 시간 밀린다")
    void asOfIsKst() {
        assertThat(service.view().asOf()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("상태마다 자기 큐의 기한을 받는다 — SLA 가 큐별로 다르다")
    void deadlinePerStatus() {
        service.view();

        // 미결제 24시간 · 발송 대기 48시간 · 배송 장기 체류 7일
        assertThat(port.requested.get("CREATED")).isEqualTo(NOW.minusHours(24));
        assertThat(port.requested.get("PAID")).isEqualTo(NOW.minusHours(48));
        assertThat(port.requested.get("SHIPPING_PENDING")).isEqualTo(NOW.minusHours(48));
        assertThat(port.requested.get("IN_TRANSIT")).isEqualTo(NOW.minusHours(24 * 7));
    }

    /**
     * 종단 상태가 큐에 섞이면 "밀린 일" 숫자가 영업 규모를 따라 계속 커진다 — 손댈 것이 없는
     * 주문이라 아무리 일해도 줄지 않는다.
     */
    @Test
    @DisplayName("종단 상태는 아예 조회하지 않는다")
    void terminalStatusesAreNotQueried() {
        service.view();

        assertThat(port.requested).doesNotContainKeys(
                "DELIVERED", "CANCELED", "REFUNDED", "REFUND_COMPLETED");
    }

    @Test
    @DisplayName("한 상태가 두 큐에 들어가지 않는다 — 겹치면 총합이 실제보다 커진다")
    void queuesDoNotOverlap() {
        Set<OrderStatus> seen = new HashSet<>();
        for (OrderQueueService.QueueDef def : OrderQueueService.QUEUES) {
            for (OrderStatus status : def.statuses()) {
                assertThat(seen.add(status)).as("중복 상태: %s", status).isTrue();
            }
        }
    }

    /** 조회되는 상태와 큐 정의가 어긋나면 어느 한쪽이 조용히 0 건이 된다. */
    @Test
    @DisplayName("조회하는 상태 집합은 큐 정의와 정확히 일치한다")
    void queriedStatusesMatchDefinitions() {
        service.view();

        List<String> defined = OrderQueueService.QUEUES.stream()
                .flatMap(d -> d.statuses().stream())
                .map(Enum::name)
                .toList();
        assertThat(port.requested.keySet()).containsExactlyInAnyOrderElementsOf(defined);
    }

    @Test
    @DisplayName("한 큐가 여러 상태를 묶으면 건수는 더하고 가장 오래된 시각은 더 이른 쪽을 고른다")
    void mergesMultiStatusBucket() {
        port.rows = List.of(
                new StatusWaiting("PAID", 3, NOW.minusHours(10), 0, 0),
                new StatusWaiting("SHIPPING_PENDING", 5, NOW.minusHours(70), 5, 0));

        QueueBucket shipping = bucket(service.view(), "AWAITING_SHIPMENT");

        assertThat(shipping.count()).isEqualTo(8);
        assertThat(shipping.overdueCount()).isEqualTo(5);
        assertThat(shipping.oldestWaitingSince()).isEqualTo(NOW.minusHours(70));
        assertThat(shipping.oldestWaitingHours()).isEqualTo(70);
    }

    /**
     * "일이 없다"와 "큐가 없어졌다"가 화면에서 같아지면 안 된다. 배포 사고로 큐 하나가 죽어도
     * 운영자에게는 그냥 한가한 날로 보인다.
     */
    @Test
    @DisplayName("건수가 0 인 큐도 목록에 남는다")
    void emptyBucketsRemain() {
        port.rows = List.of();

        OrderQueues queues = service.view();

        assertThat(queues.buckets()).hasSize(OrderQueueService.QUEUES.size());
        assertThat(queues.buckets()).allSatisfy(b -> {
            assertThat(b.count()).isZero();
            assertThat(b.oldestWaitingSince()).isNull();
            assertThat(b.oldestWaitingHours()).isNull();
        });
        assertThat(queues.totalCount()).isZero();
    }

    @Test
    @DisplayName("총합은 큐를 더한 값이다")
    void totalsAreSums() {
        port.rows = List.of(
                new StatusWaiting("CREATED", 2, NOW.minusHours(30), 1, 0),
                new StatusWaiting("REFUND_REQUESTED", 4, NOW.minusHours(50), 3, 0));

        OrderQueues queues = service.view();

        assertThat(queues.totalCount()).isEqualTo(6);
        assertThat(queues.totalOverdue()).isEqualTo(4);
    }

    /**
     * 이력이 없어 주문 일시로 대신 잰 건수는 <b>그대로 올라와야</b> 한다. 이 값이 0 으로 뭉개지면
     * V50 이전에 만들어진 옛 주문 한 건이 "3년째 밀린 일" 자리를 영구히 차지하는데, 화면에는
     * 그것이 추정값이라는 표시가 어디에도 없다.
     */
    @Test
    @DisplayName("주문 일시로 대신 잰 건수는 큐마다 합쳐 올라온다")
    void estimatedAgeCountIsReported() {
        port.rows = List.of(
                new StatusWaiting("PAID", 3, NOW.minusHours(10), 0, 2),
                new StatusWaiting("SHIPPING_PENDING", 5, NOW.minusHours(70), 5, 1));

        assertThat(bucket(service.view(), "AWAITING_SHIPMENT").ageFromOrderDateCount())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("큐는 자기가 어떤 상태를 세는지 밝힌다 — 화면이 그 상태로 목록을 열 수 있어야 한다")
    void bucketDeclaresItsStatuses() {
        OrderQueues queues = service.view();

        assertThat(bucket(queues, "AWAITING_SHIPMENT").statuses())
                .containsExactly("PAID", "SHIPPING_PENDING");
        assertThat(bucket(queues, "UNPAID").statuses()).containsExactly("CREATED");
        assertThat(bucket(queues, "UNPAID").slaHours()).isEqualTo(24);
    }

    /** 어댑터가 모르는 상태를 돌려줘도 큐 정의에 없으면 세지 않는다. */
    @Test
    @DisplayName("큐 정의에 없는 상태 행은 무시한다")
    void unknownStatusRowsAreIgnored() {
        port.rows = List.of(new StatusWaiting("DELIVERED", 999, NOW.minusHours(1), 999, 0));

        assertThat(service.view().totalCount()).isZero();
    }
}
