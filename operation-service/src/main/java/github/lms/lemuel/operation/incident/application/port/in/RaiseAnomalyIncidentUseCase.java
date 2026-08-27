package github.lms.lemuel.operation.incident.application.port.in;

import java.time.Instant;

/**
 * 이상 탐지 판정 1건을 인시던트 라이프사이클에 반영하는 <b>인바운드 포트</b>.
 *
 * <p>이 포트가 생긴 이유: 이전에는 {@code anomaly.application.service.AnomalyIncidentApplier} 가
 * incident 의 도메인({@code Incident}·{@code IncidentSeverity}·{@code TimelineEventType} …)과
 * 아웃바운드 포트를 직접 다뤘다. <b>incident 의 로직이 anomaly 패키지에 살고 있었다</b>는 뜻이다.
 * 인시던트 상태머신이 바뀔 때마다 anomaly 가 같이 깨졌다.
 *
 * <p>그래서 로직은 incident 로 되돌리고, anomaly 에는 이 포트만 남긴다. 시그니처에
 * incident 도메인 타입이 <b>하나도 없다</b>는 점이 핵심이다 — 아래 {@link Command}·{@link Result} 는
 * 모두 이 포트가 소유한다. 호출자는 "무엇을 관측했는지" 만 말하고,
 * 그것이 인시던트로서 무슨 의미인지는 incident 가 정한다.
 */
public interface RaiseAnomalyIncidentUseCase {

    /**
     * 판정 1건을 반영한다 — 신규 OPEN / refire / 자동 해제 중 하나이거나, 아무 일도 없다.
     *
     * <p><b>독립 트랜잭션</b>으로 실행된다. 호출자는 실패 시 이 메서드를 다시 호출하는 방식으로만
     * 재시도해야 한다(같은 트랜잭션 재사용 불가 — 충돌한 트랜잭션은 rollback-only 로 오염된다).
     */
    Result apply(Command command);

    /**
     * 관측 결과. incident 어휘가 아니라 <b>탐지자의 어휘</b>로 기술한다.
     *
     * @param metricKey    관측 대상 metric_key — 인시던트의 correlation key 가 된다
     * @param categoryName 신호 분류 이름. 알 수 없는 값이면 incident 가 UNKNOWN 으로 처리한다
     *                     (enum 을 노출하지 않으려고 문자열로 받는다)
     * @param anomaly      이상으로 판정됐는가
     * @param critical     이상이면서 심각한가 — false 면 경고 수준
     * @param reason       사람이 읽을 판정 근거 — 타임라인에 남는다
     * @param zScore       판정 z 값 — 로그·타임라인 문구에만 쓰인다
     * @param resolveEligible 정상 복귀가 충분히 지속돼 자동 해제 자격을 갖췄는가
     *                        ({@code anomaly=false} 일 때만 의미 있다)
     * @param observedAt   관측 시각
     */
    record Command(
            String metricKey,
            String categoryName,
            boolean anomaly,
            boolean critical,
            String reason,
            double zScore,
            boolean resolveEligible,
            Instant observedAt
    ) {
    }

    /** 반영 결과 — 호출자 집계용. */
    enum Result {
        OPENED, REFIRED, AUTO_RESOLVED, NONE
    }
}
