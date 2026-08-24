package github.lms.lemuel.common.outbox.adapter.in.web;

import github.lms.lemuel.common.outbox.application.port.in.OutboxAdminUseCase;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox DLQ 운영자 콘솔 REST API.
 *
 * <p>order-service / settlement-service 양쪽 모두 같은 outbox 테이블을 공유하므로 (단일 DB)
 * shared-common 에 위치시켜 양 서비스에서 자동 활성화된다 — 별도 ComponentScan 추가 불필요.
 *
 * <p>접근 제어: SecurityConfig 에서 {@code /admin/**} 경로에 운영자 권한을 강제해야 한다.
 */
@Tag(name = "Outbox DLQ Admin", description = "Outbox 재시도 한계 초과 이벤트의 재처리/스킵 API")
@RestController
@RequestMapping("/admin/outbox")
public class OutboxAdminController {

    private final OutboxAdminUseCase outboxAdminUseCase;

    public OutboxAdminController(OutboxAdminUseCase outboxAdminUseCase) {
        this.outboxAdminUseCase = outboxAdminUseCase;
    }

    @Operation(summary = "DLQ (FAILED) 이벤트 페이지 조회",
            description = "재시도 한계 초과로 발행 실패한 outbox 이벤트 목록. lastError 로 실패 원인 파악 가능.")
    @GetMapping("/dlq")
    public ResponseEntity<DlqPageResponse> listDlq(
            @Parameter(description = "0 부터 시작") @RequestParam(defaultValue = "0") int offset,
            @Parameter(description = "최대 100") @RequestParam(defaultValue = "20") int limit) {
        List<OutboxEvent> failed = outboxAdminUseCase.listFailed(offset, limit);
        long total = outboxAdminUseCase.failedCount();
        return ResponseEntity.ok(new DlqPageResponse(total, failed.stream().map(OutboxAdminController::toItem).toList()));
    }

    @Operation(summary = "FAILED 이벤트 재처리 (PENDING 으로 복원)",
            description = "외부 시스템 장애 복구 후 운영자가 호출. retryCount 0 으로 초기화되어 다음 폴링에서 즉시 재시도.")
    @PostMapping("/dlq/{eventId}/retry")
    public ResponseEntity<DlqItemResponse> retry(@PathVariable UUID eventId) {
        OutboxEvent event = outboxAdminUseCase.retry(eventId);
        return ResponseEntity.ok(toItem(event));
    }

    @Operation(summary = "FAILED 이벤트 스킵 (PUBLISHED 강제 마킹)",
            description = "이미 다른 경로로 보정 완료된 이벤트를 outbox 에서 정리. lastError 에 [SKIPPED] 사유가 영구 기록됨.")
    @PostMapping("/dlq/{eventId}/skip")
    public ResponseEntity<DlqItemResponse> skip(@PathVariable UUID eventId,
                                                 @RequestBody SkipRequest request) {
        String operatorId = currentOperatorId();
        OutboxEvent event = outboxAdminUseCase.skip(eventId, request.reason(), operatorId);
        return ResponseEntity.ok(toItem(event));
    }

    private static String currentOperatorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return "anonymous";
        }
        return auth.getName();
    }

    private static DlqItemResponse toItem(OutboxEvent e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", e.getEventId());
        body.put("aggregateType", e.getAggregateType());
        body.put("aggregateId", e.getAggregateId());
        body.put("eventType", e.getEventType());
        body.put("status", e.getStatus().name());
        body.put("retryCount", e.getRetryCount());
        body.put("lastError", e.getLastError());
        body.put("createdAt", e.getCreatedAt());
        body.put("publishedAt", e.getPublishedAt());
        return new DlqItemResponse(body);
    }

    public record DlqPageResponse(long totalFailed, List<DlqItemResponse> items) {}

    public record DlqItemResponse(Map<String, Object> event) {
        @SuppressWarnings("unused")
        public LocalDateTime createdAt() {
            return (LocalDateTime) event.get("createdAt");
        }
    }

    public record SkipRequest(String reason) {}
}
