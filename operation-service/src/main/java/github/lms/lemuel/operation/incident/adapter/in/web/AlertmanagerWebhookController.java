package github.lms.lemuel.operation.incident.adapter.in.web;

import github.lms.lemuel.operation.incident.adapter.in.web.dto.AlertmanagerPayload;
import github.lms.lemuel.operation.incident.application.port.in.IngestAlertUseCase;
import github.lms.lemuel.operation.incident.application.port.in.IngestAlertUseCase.AlertCommand;
import github.lms.lemuel.operation.incident.application.port.in.IngestAlertUseCase.IngestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Alertmanager webhook 수신 어댑터.
 *
 * <p>인증은 {@code OpsWebhookAuthFilter}(Bearer INTERNAL_API_KEY) 가 담당 — 이 경로는
 * 보안 체인에서 permitAll 이다. 응답은 <b>항상 200</b>: 5xx 를 돌려주면 Alertmanager 가
 * 그룹 알림 전체를 재시도해 폭주하므로, 부분 실패는 로그·집계로만 남기고
 * 유실은 repeat_interval 재전송이 보상한다.
 */
@RestController
@RequestMapping("/api/ops/webhook")
public class AlertmanagerWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertmanagerWebhookController.class);

    private final IngestAlertUseCase ingestAlertUseCase;

    public AlertmanagerWebhookController(IngestAlertUseCase ingestAlertUseCase) {
        this.ingestAlertUseCase = ingestAlertUseCase;
    }

    @PostMapping("/alertmanager")
    public ResponseEntity<IngestResult> receive(@RequestBody AlertmanagerPayload payload) {
        List<AlertmanagerPayload.Alert> alerts = payload.alerts() == null ? List.of() : payload.alerts();
        log.debug("Alertmanager 알림 수신: groupKey={} status={} alerts={}",
                payload.groupKey(), payload.status(), alerts.size());

        List<AlertCommand> commands = alerts.stream()
                .filter(a -> a.fingerprint() != null && !a.fingerprint().isBlank())
                .map(a -> new AlertCommand(a.fingerprint(), a.isFiring(),
                        a.labels(), a.annotations(), a.startsAt(), a.normalizedEndsAt()))
                .toList();

        return ResponseEntity.ok(ingestAlertUseCase.ingest(commands));
    }
}
