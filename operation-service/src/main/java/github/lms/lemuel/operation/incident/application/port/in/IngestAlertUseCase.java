package github.lms.lemuel.operation.incident.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Alertmanager webhook 알람 반영 유스케이스.
 *
 * <p>처리 단위는 그룹 알림이 아니라 개별 alert — fingerprint 가 correlation key.
 * alert 1건 = 1 트랜잭션으로 처리해 한 건의 실패가 배치 전체를 막지 않는다
 * (응답은 항상 200 — Alertmanager 재시도 폭주 방지, 유실은 repeat_interval 재전송이 보상).
 */
public interface IngestAlertUseCase {

    IngestResult ingest(List<AlertCommand> alerts);

    /**
     * 웹 어댑터가 Alertmanager v4 페이로드에서 변환한 개별 alert 명령.
     *
     * @param firing true=firing / false=resolved
     * @param endsAt resolved alert 의 해제 시각 (firing 은 zero-value "0001-01-01" 이 오므로 null 정규화)
     */
    record AlertCommand(
            String fingerprint,
            boolean firing,
            Map<String, String> labels,
            Map<String, String> annotations,
            Instant startsAt,
            Instant endsAt
    ) {
    }

    /** @param failed 처리 중 예외가 난 alert 수 (로그로만 남기고 응답은 200) */
    record IngestResult(int received, int applied, int failed) {
    }
}
