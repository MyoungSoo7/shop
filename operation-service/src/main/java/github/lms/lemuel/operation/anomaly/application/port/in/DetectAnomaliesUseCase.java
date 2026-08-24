package github.lms.lemuel.operation.anomaly.application.port.in;

/**
 * 이상 탐지 1회 실행 인바운드 포트 — 스케줄러(또는 테스트)가 호출한다.
 *
 * <p>구현은 설정된 metric_key 전체를 순회하며 직전 마감 버킷을 판정하고,
 * 이상이면 {@code source=ANOMALY} 인시던트를 생성/refire, 정상 복귀가 지속되면 자동 해제한다.
 */
public interface DetectAnomaliesUseCase {

    DetectionSummary detectOnce();

    /**
     * 1회 스캔 집계.
     *
     * @param scanned  판정을 시도한 metric 수
     * @param opened   신규로 연 인시던트 수
     * @param refired  기존 활성 인시던트에 병합(refire)한 수
     * @param resolved 정상 복귀로 자동 해제한 수
     * @param skipped  히스토리 부족 등으로 판정을 건너뛴 metric 수
     */
    record DetectionSummary(int scanned, int opened, int refired, int resolved, int skipped) {
    }
}
