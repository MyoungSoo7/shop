package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.application.port.in.GetPendingStockReclaimUseCase;
import github.lms.lemuel.order.application.port.in.GetPendingStockReclaimUseCase.PendingReclaim;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 회수 대기 재고 운영 콘솔.
 *
 * <pre>
 *   GET /admin/stock-reclaim?limit=100
 * </pre>
 *
 * <p>배송된 물건에 환불·취소가 끝났는데 아직 물건이 돌아오지 않은 주문을 오래 묶인 순으로 보여준다.
 * 이 수량은 판매 가능 재고로 복귀하지 않았고 고객에게는 이미 환불된 상태라, 방치하면 팔 수 있는 물건이
 * 영영 묶인다. 운영자는 이 목록으로 택배 회수를 독촉하거나 손실 처리를 판단한다.
 *
 * <p>회수가 확정되면 배송 도메인의 반품 처리(markReturned)가 재고를 되돌리고 이 목록에서 사라진다.
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/stock-reclaim/**} 매처(ADMIN/MANAGER)로 제한된다 —
 * 조회 전용이라 실행 콘솔과 달리 MANAGER 에게도 연다.
 */
@RestController
@RequestMapping("/admin/stock-reclaim")
public class AdminStockReclaimController {

    private static final int DEFAULT_LIMIT = 100;

    private final GetPendingStockReclaimUseCase useCase;

    public AdminStockReclaimController(GetPendingStockReclaimUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "회수 대기 재고 조회",
            description = "배송 후 환불·취소로 재고 원복이 보류된 주문을 오래 묶인 순으로 조회한다.")
    @GetMapping
    public ResponseEntity<PendingReclaimSummary> list(
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        List<PendingReclaim> items = useCase.findPending(LocalDateTime.now(), limit);
        int totalQuantity = items.stream().mapToInt(PendingReclaim::totalQuantity).sum();
        return ResponseEntity.ok(new PendingReclaimSummary(items.size(), totalQuantity, items));
    }

    /**
     * @param totalOrders   회수 대기 주문 수
     * @param totalQuantity 묶여 있는 총 수량 — 운영자가 규모를 한눈에 보는 값
     */
    public record PendingReclaimSummary(int totalOrders, int totalQuantity, List<PendingReclaim> items) {
    }
}
