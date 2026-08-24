package github.lms.lemuel.operation.notification.adapter.in.web;

import github.lms.lemuel.operation.notification.application.ChannelResult;
import github.lms.lemuel.operation.notification.application.DispatchResult;
import github.lms.lemuel.operation.notification.application.port.in.DispatchNotificationUseCase;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 알림 수기 발송·데모 경로.
 *
 * <p><b>경로가 {@code /internal/**} 인 이유</b>: 이관 전 폴리글랏 서비스에서 이 두 엔드포인트는
 * 인증 없이 발송하는 내부 경로라 게이트웨이에 일부러 올리지 않았다(스트림만 노출). operation-service
 * 안으로 들어오면서 같은 성질을 유지하려면 <b>게이트웨이가 라우팅하지 않는 접두사</b>에 둬야 한다 —
 * shared-common 보안 체인이 {@code /internal/**} 을 공유 시크릿 필터(InternalApiKeyFilter)에
 * 맡기므로, 외부 유입은 게이트웨이 미라우팅 + 키 게이트로 이중 차단된다.
 * {@code /api/notifications/send} 로 뒀다면 게이트웨이 스트림 라우트와 접두사를 공유해
 * 실수로 열릴 여지가 생긴다.
 */
@RestController
@RequestMapping("/internal/notifications")
public class NotificationController {

    private final DispatchNotificationUseCase dispatcher;

    public NotificationController(DispatchNotificationUseCase dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/send")
    public ResponseEntity<DispatchResponse> send(@RequestBody SendNotificationRequest request) {
        Notification notification = new Notification(
                parseType(request.type()),
                request.recipient(),
                request.subject(),
                request.body(),
                request.eventId());
        return ResponseEntity.ok(toResponse(dispatcher.dispatch(notification)));
    }

    @GetMapping("/demo")
    public DispatchResponse demo() {
        Notification sample = new Notification(
                NotificationType.SETTLEMENT_CONFIRMED,
                "ops@lemuel.co.kr",
                "데모 정산 확정",
                "알림 슬라이스 데모 — 모든 활성 채널로 팬아웃합니다.",
                "demo-" + UUID.randomUUID());
        return toResponse(dispatcher.dispatch(sample));
    }

    /** 미상 타입은 거부하지 않고 GENERIC 으로 흡수한다 — 데모·수기 발송이 타입 오타로 막히지 않게. */
    private static NotificationType parseType(String raw) {
        if (raw == null) {
            return NotificationType.GENERIC;
        }
        try {
            return NotificationType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NotificationType.GENERIC;
        }
    }

    private static DispatchResponse toResponse(DispatchResult result) {
        List<ChannelResultDto> results = result.results().stream()
                .map(r -> switch (r) {
                    case ChannelResult.Success s -> new ChannelResultDto(s.channel(), "SUCCESS", s.attempts(), null);
                    case ChannelResult.Failure f ->
                            new ChannelResultDto(f.channel(), "FAILURE", f.attempts(), f.error());
                })
                .toList();
        return new DispatchResponse(result.deduped(), result.allSucceeded(), results);
    }

    /** 인바운드 REST 요청. */
    public record SendNotificationRequest(
            String type,
            String recipient,
            String subject,
            String body,
            String eventId
    ) {
    }

    /** 응답 DTO — 평평하고 JSON 친화적으로. */
    public record ChannelResultDto(String channel, String status, int attempts, String error) {
    }

    public record DispatchResponse(boolean deduped, boolean allSucceeded, List<ChannelResultDto> results) {
    }
}
