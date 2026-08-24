package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.payment.application.port.in.GetRefundHistoryUseCase;
import github.lms.lemuel.payment.domain.Refund;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 환불 콘솔 — 상태별 환불 조회.
 *
 * <pre>
 *   GET /admin/refunds?status=FAILED
 * </pre>
 *
 * 자동 재시도로도 복구되지 않은(재시도 상한 도달) 실패 환불을 운영자가 훑어 수동 개입할 때 쓴다.
 * 권한은 SecurityConfig 의 {@code /admin/refunds/**} 매처(ADMIN/MANAGER)로 제한된다.
 */
@RestController
@RequestMapping("/admin/refunds")
public class AdminRefundController {

    private final GetRefundHistoryUseCase getRefundHistoryUseCase;

    public AdminRefundController(GetRefundHistoryUseCase getRefundHistoryUseCase) {
        this.getRefundHistoryUseCase = getRefundHistoryUseCase;
    }

    @GetMapping
    public ResponseEntity<List<AdminRefundItem>> byStatus(
            @RequestParam(name = "status", defaultValue = "FAILED") Refund.Status status) {
        List<AdminRefundItem> items = getRefundHistoryUseCase.getRefundsByStatus(status).stream()
                .map(r -> new AdminRefundItem(
                        r.getId(), r.getPaymentId(), r.getAmount(), r.getStatus().name(),
                        r.getRetryCount(), r.isRetryExhausted(), r.getNextRetryAt(),
                        r.getIdempotencyKey(), r.getReason(),
                        r.getRequestedAt(), r.getCompletedAt()))
                .toList();
        return ResponseEntity.ok(items);
    }

    public record AdminRefundItem(
            Long id,
            Long paymentId,
            BigDecimal amount,
            String status,
            int retryCount,
            boolean retryExhausted,
            LocalDateTime nextRetryAt,
            String idempotencyKey,
            String reason,
            LocalDateTime requestedAt,
            LocalDateTime completedAt
    ) {}
}
