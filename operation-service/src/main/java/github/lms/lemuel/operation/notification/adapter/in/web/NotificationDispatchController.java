package github.lms.lemuel.operation.notification.adapter.in.web;

import github.lms.lemuel.operation.notification.application.ChannelResult;
import github.lms.lemuel.operation.notification.application.DispatchRecord;
import github.lms.lemuel.operation.notification.application.port.in.NotificationHistoryUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 발송 이력 운영 콘솔 API — "그 사람한테 알림이 갔나?" 에 조회로 답하는 경로.
 *
 * <p><b>경로가 {@code /api/ops/**} 인 이유</b>: {@code OperationSecurityConfig}(@Order(1)) 가 이미
 * 이 접두사 전체에 {@code hasRole("ADMIN")} 을 걸고 있다. {@code /admin/notifications/**} 로 뒀다면
 * 새 보안 체인과 matcher 를 따로 만들어야 하고, <b>체인을 하나 더 만드는 것 자체가 위험</b>이다 —
 * securityMatcher 가 어긋나면 조용히 인증 없는 경로가 생긴다. 이미 검증된 체인 안에 들어가는 편이 안전하다.
 *
 * <p>발송 경로({@code NotificationController}, {@code /internal/**})와 접두사가 다른 것도 의도다.
 * 저쪽은 기계(내부 서비스)가 공유 시크릿으로 부르고, 이쪽은 사람이 JWT 로 부른다.
 */
@RestController
@RequestMapping("/api/ops/notifications/dispatches")
public class NotificationDispatchController {

    private final NotificationHistoryUseCase history;

    public NotificationDispatchController(NotificationHistoryUseCase history) {
        this.history = history;
    }

    /**
     * @param status    DELIVERED / PARTIAL / FAILED / PENDING / NO_CHANNEL 중 하나(정확일치)
     * @param recipient 수신자 정확일치
     */
    @GetMapping
    public PageResponse list(@RequestParam(required = false) String status,
                             @RequestParam(required = false) String recipient,
                             @RequestParam(defaultValue = "50") int limit,
                             @RequestParam(defaultValue = "0") int offset) {
        NotificationHistoryUseCase.Page page = history.list(status, recipient, limit, offset);
        return new PageResponse(
                page.items().stream().map(DispatchSummary::from).toList(),
                page.total(),
                page.limit(),
                page.offset());
    }

    @GetMapping("/{id}")
    public DispatchDetail detail(@PathVariable long id) {
        return history.detail(id)
                .map(DispatchDetail::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "dispatch not found: " + id));
    }

    /**
     * 원본을 그대로 다시 보낸다. 원본 행은 그대로 남고 <b>새 행</b>이 생긴다.
     *
     * <p>{@code idempotencyKey} 를 주면 같은 키의 재발송은 한 번만 나간다 — 콘솔 더블클릭 방어.
     */
    @PostMapping("/{id}/resend")
    public ResendResponse resend(@PathVariable long id,
                                 @RequestBody(required = false) ResendRequest request) {
        String key = request == null ? null : request.idempotencyKey();
        return history.resend(id, key)
                .map(ResendResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "dispatch not found: " + id));
    }

    // ───────────────────────────── DTO ─────────────────────────────

    public record ResendRequest(String idempotencyKey) {
    }

    /** 목록 행 — 본문은 뺀다(목록에 본문 전체를 실으면 응답이 통째로 무거워진다). */
    public record DispatchSummary(long id, String eventId, String type, String recipient, String subject,
                                  String status, int channelsTotal, int channelsSucceeded, Long resentFromId,
                                  Instant createdAt, Instant completedAt) {

        static DispatchSummary from(DispatchRecord record) {
            return new DispatchSummary(record.id(), record.eventId(), record.type(), record.recipient(),
                    record.subject(), record.status(), record.channelsTotal(), record.channelsSucceeded(),
                    record.resentFromId(), record.createdAt(), record.completedAt());
        }
    }

    public record DispatchDetail(long id, String eventId, String type, String recipient, String subject,
                                 String body, String status, int channelsTotal, int channelsSucceeded,
                                 Long resentFromId, Instant createdAt, Instant completedAt,
                                 List<ChannelOutcomeDto> channels) {

        static DispatchDetail from(DispatchRecord record) {
            return new DispatchDetail(record.id(), record.eventId(), record.type(), record.recipient(),
                    record.subject(), record.body(), record.status(), record.channelsTotal(),
                    record.channelsSucceeded(), record.resentFromId(), record.createdAt(), record.completedAt(),
                    record.channels().stream()
                            .map(c -> new ChannelOutcomeDto(c.channel(), c.status(), c.attempts(), c.error(),
                                    c.createdAt()))
                            .toList());
        }
    }

    public record ChannelOutcomeDto(String channel, String status, int attempts, String error, Instant createdAt) {
    }

    public record PageResponse(List<DispatchSummary> items, long total, int limit, int offset) {
    }

    /**
     * @param deduped 같은 idempotencyKey 로 이미 재발송된 건이라 실제로는 보내지 않았다는 뜻.
     *                실패가 아니므로 콘솔은 이 값을 성공과 구분해 보여줘야 한다.
     */
    public record ResendResponse(long originalId, String eventId, boolean deduped, boolean allSucceeded,
                                 List<ChannelOutcomeDto> results) {

        static ResendResponse from(NotificationHistoryUseCase.Resent resent) {
            List<ChannelOutcomeDto> results = resent.result().results().stream()
                    .map(r -> switch (r) {
                        case ChannelResult.Success s ->
                                new ChannelOutcomeDto(s.channel(), "SUCCESS", s.attempts(), null, null);
                        case ChannelResult.Failure f ->
                                new ChannelOutcomeDto(f.channel(), "FAILURE", f.attempts(), f.error(), null);
                    })
                    .toList();
            return new ResendResponse(resent.originalId(), resent.eventId(), resent.result().deduped(),
                    resent.result().allSucceeded(), results);
        }
    }
}
