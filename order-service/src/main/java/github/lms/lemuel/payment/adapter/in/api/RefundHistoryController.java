package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.payment.application.port.in.GetRefundHistoryUseCase;
import github.lms.lemuel.payment.domain.Refund;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * T3-⑦(c) — 결제별 환불 이력 조회 API.
 *
 * <pre>
 *   GET /api/payments/{paymentId}/refunds
 * </pre>
 *
 * 관리자·사용자 모두 호출 가능 (SecurityConfig 의 요청 매처로 관리).
 */
@RestController
@RequestMapping("/api/payments")
public class RefundHistoryController {

    private final GetRefundHistoryUseCase getRefundHistoryUseCase;

    public RefundHistoryController(GetRefundHistoryUseCase getRefundHistoryUseCase) {
        this.getRefundHistoryUseCase = getRefundHistoryUseCase;
    }

    @GetMapping("/{paymentId}/refunds")
    public ResponseEntity<RefundHistoryResponse> getByPayment(@PathVariable Long paymentId) {
        List<Refund> refunds = getRefundHistoryUseCase.getRefundsByPaymentId(paymentId);

        BigDecimal totalRefunded = refunds.stream()
                .filter(Refund::isCompleted)
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<RefundItem> items = refunds.stream()
                .map(r -> new RefundItem(
                        r.getId(), r.getAmount(), r.getStatus().name(),
                        r.getIdempotencyKey(), r.getReason(),
                        r.getRequestedAt(), r.getCompletedAt()))
                .toList();

        return ResponseEntity.ok(new RefundHistoryResponse(paymentId, totalRefunded, items));
    }

    public record RefundHistoryResponse(
            Long paymentId,
            BigDecimal totalRefundedAmount,
            List<RefundItem> refunds
    ) {}

    public record RefundItem(
            Long id,
            BigDecimal amount,
            String status,
            String idempotencyKey,
            String reason,
            LocalDateTime requestedAt,
            LocalDateTime completedAt
    ) {}
}
