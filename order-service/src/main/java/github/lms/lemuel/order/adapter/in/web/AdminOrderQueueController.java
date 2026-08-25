package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.common.web.csv.CsvResponse;
import github.lms.lemuel.common.web.csv.ExportScope;
import github.lms.lemuel.order.application.port.in.ViewOrderQueuesUseCase;
import github.lms.lemuel.order.application.port.in.ViewOrderQueuesUseCase.OrderQueues;
import github.lms.lemuel.order.application.port.in.ViewOrderQueuesUseCase.QueueBucket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 상태별 작업 큐 — 지금 밀려 있는 일.
 *
 * <pre>
 *   GET /admin/order-queues          → 큐별 건수 · 최장 대기 · 기한 초과
 *   GET /admin/order-queues/export   → 같은 내용의 CSV
 * </pre>
 *
 * <p><b>목록을 여기서 주지 않는 이유</b>: 큐를 눌러 들어가는 화면은 이미 있다
 * ({@code GET /orders/admin?status=…}). 같은 목록을 여기서 한 번 더 구현하면 정렬·필터·페이지
 * 규칙이 두 벌이 되고, 둘이 어긋나는 날 어느 쪽이 맞는지 판단할 근거가 없다. 이 진입점은
 * <b>세는 일</b>만 한다.
 *
 * <p><b>{@code /orders/admin/summary} 와 겹치지 않는가</b>: 그쪽은 상태별 건수·금액이고 여기는
 * 대기 시간이다. 취소 신청 12건이 방금 들어온 12건인지 사흘 묵은 12건인지는 건수로 구분되지
 * 않는데, 운영자가 실제로 판단해야 하는 것은 그 차이다.
 *
 * <p><b>권한</b>: SecurityConfig 의 {@code /admin/order-queues/**} 매처로 제한된다. 이 설정에는
 * 포괄 {@code /admin/**} 매처가 없어 매처를 빠뜨린 경로는 에러가 아니라 조용히
 * {@code anyRequest().authenticated()} 로 떨어진다 — 로그인만 하면 누구나 호출한다는 뜻이다.
 * 리뷰·환불 콘솔과 같은 CS 업무라 MANAGER 에게도 연다.
 */
@Tag(name = "Admin Order Queue", description = "상태별 작업 큐 — 밀린 일과 대기 시간")
@RestController
@RequestMapping("/admin/order-queues")
@RequiredArgsConstructor
public class AdminOrderQueueController {

    /** 집계 기준 시각. 기한 초과 판정이 이 시각 기준이라 파일에도 남긴다. */
    static final String HEADER_AS_OF = "X-Export-As-Of";

    private final ViewOrderQueuesUseCase viewOrderQueuesUseCase;

    @GetMapping
    @Operation(summary = "작업 큐 현황",
            description = "큐별 건수·최장 대기 시간·기한 초과 건수. 건수가 0 인 큐도 남는다")
    public ResponseEntity<OrderQueues> view() {
        return ResponseEntity.ok(viewOrderQueuesUseCase.view());
    }

    /**
     * 큐 현황 CSV.
     *
     * <p>{@link ExportScope#of} 에 {@code truncated=false} 를 주는 것이 맞다 — 큐 정의는 고정
     * 목록이라 잘라낼 일이 없고, 행 수가 곧 전체 건수다.
     */
    @GetMapping("/export")
    @Operation(summary = "작업 큐 CSV", description = "화면과 같은 내용. 잘라내기 없음")
    public ResponseEntity<ByteArrayResource> export() {
        OrderQueues queues = viewOrderQueuesUseCase.view();

        return CsvResponse.of(
                "order_queues",
                List.of("큐", "이름", "포함상태", "건수", "최장대기시작", "최장대기시간", "기한(시간)",
                        "기한초과", "주문일시로대신잼"),
                queues.buckets(),
                AdminOrderQueueController::toCells,
                ExportScope.of(queues.buckets().size(), false)
                        .with(HEADER_AS_OF, queues.asOf().toString()));
    }

    private static List<String> toCells(QueueBucket bucket) {
        return List.of(
                bucket.key(),
                bucket.label(),
                String.join(" ", bucket.statuses()),
                String.valueOf(bucket.count()),
                Objects.toString(bucket.oldestWaitingSince(), ""),
                Objects.toString(bucket.oldestWaitingHours(), ""),
                String.valueOf(bucket.slaHours()),
                String.valueOf(bucket.overdueCount()),
                String.valueOf(bucket.ageFromOrderDateCount()));
    }
}
