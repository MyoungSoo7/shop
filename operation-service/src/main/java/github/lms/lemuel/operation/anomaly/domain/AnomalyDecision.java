package github.lms.lemuel.operation.anomaly.domain;

/**
 * 버킷 1개 판정의 완전한 근거 (순수 VO) — 인시던트 생성 시 사람이 읽는 설명과
 * severity 결정의 재료가 된다.
 *
 * <p>severity 는 {@code critical}(z >= criticalZ) 힌트만 담고, {@code IncidentSeverity} 로의
 * 실제 매핑은 application 계층이 한다(도메인이 incident BC 에 결합되지 않도록).
 *
 * @param verdict              NORMAL / ANOMALY
 * @param zScore               (failureRate - mean) / stddev. 베이스라인 퇴화 시 0.0.
 * @param baselineMean         베이스라인 평균
 * @param baselineStddev       베이스라인 표준편차
 * @param evaluatedFailureRate 판정 버킷의 failure_rate
 * @param evaluatedCountTotal  판정 버킷의 count_total (표본 수)
 * @param critical             CRITICAL 승격 대상 여부 (z >= criticalZ)
 * @param reason               판정 근거 요약 (인시던트 description 용)
 */
public record AnomalyDecision(
        AnomalyVerdict verdict,
        double zScore,
        double baselineMean,
        double baselineStddev,
        double evaluatedFailureRate,
        long evaluatedCountTotal,
        boolean critical,
        String reason
) {

    public boolean isAnomaly() {
        return verdict == AnomalyVerdict.ANOMALY;
    }
}
