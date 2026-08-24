package github.lms.lemuel.operation.anomaly.domain;

/**
 * 이상 판정 임계 묶음 (순수 VO) — 전부 application.yml {@code app.ops.anomaly.*} 로 외부화되며,
 * 도메인은 프레임워크 의존 없이 이 값만 받아 판정한다(재배포 없이 튜닝 가능).
 *
 * @param zThreshold        이상으로 볼 z-score 하한 (예 3.0). 이 값 이상이어야 이상 후보.
 * @param criticalZ         CRITICAL severity 로 승격하는 z-score 하한 (예 5.0). 미만은 WARNING.
 * @param windowSize        롤링 베이스라인에 쓰는 직전 버킷 수 (예 12 = 1시간).
 * @param minSampleTotal    최소 표본 게이트 — 판정 버킷의 count_total 이 이 값 미만이면 미판정(저표본 오탐 차단).
 * @param failureRateFloor  상대임계 하한 — 판정 버킷 failure_rate 가 이 값 미만이면 미판정(절대적으로 낮은 실패율은 이상 아님).
 * @param resolveStreakK    정상 복귀 자동해제 조건 — 이 횟수만큼 연속 정상(게이트 미충족) 버킷이면 활성 인시던트 자동 해제.
 */
public record AnomalyThreshold(
        double zThreshold,
        double criticalZ,
        int windowSize,
        long minSampleTotal,
        double failureRateFloor,
        int resolveStreakK
) {

    public AnomalyThreshold {
        if (windowSize <= 1) {
            throw new IllegalArgumentException("windowSize must be > 1: " + windowSize);
        }
        if (resolveStreakK < 1) {
            throw new IllegalArgumentException("resolveStreakK must be >= 1: " + resolveStreakK);
        }
        if (criticalZ < zThreshold) {
            throw new IllegalArgumentException(
                    "criticalZ(%s) must be >= zThreshold(%s)".formatted(criticalZ, zThreshold));
        }
    }
}
