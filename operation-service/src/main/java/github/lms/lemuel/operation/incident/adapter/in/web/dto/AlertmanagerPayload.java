package github.lms.lemuel.operation.incident.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Alertmanager webhook v4 페이로드 (prom/alertmanager v0.27).
 *
 * <p>처리 단위는 그룹이 아니라 {@code alerts[]} 의 개별 alert — fingerprint 가 correlation key.
 * firing alert 의 endsAt 은 zero-value("0001-01-01T00:00:00Z")로 오므로 사용 측에서
 * {@link Alert#normalizedEndsAt()} 로 정규화한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerPayload(
        String version,
        String groupKey,
        String status,
        String receiver,
        List<Alert> alerts
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alert(
            String status,
            String fingerprint,
            Map<String, String> labels,
            Map<String, String> annotations,
            Instant startsAt,
            Instant endsAt
    ) {
        private static final Instant ZERO_VALUE_CUTOFF = Instant.parse("0002-01-01T00:00:00Z");

        public boolean isFiring() {
            return "firing".equalsIgnoreCase(status);
        }

        /** Go zero-value("0001-01-01") 를 null 로 정규화한 해제 시각. */
        public Instant normalizedEndsAt() {
            return endsAt == null || endsAt.isBefore(ZERO_VALUE_CUTOFF) ? null : endsAt;
        }
    }
}
