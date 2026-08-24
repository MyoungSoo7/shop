package github.lms.lemuel.operation.anomaly.domain;

/**
 * 이상 판정 순수 함수 — 판정 버킷의 failure_rate 를 롤링 베이스라인 대비 z-score 로 재고,
 * 3중 게이트(최소표본·상대임계·z 임계)를 모두 통과할 때만 이상으로 본다.
 *
 * <p>게이트 순서와 의미:
 * <ol>
 *   <li><b>퇴화 게이트</b> — 베이스라인 표준편차 0(또는 표본 0)이면 z 정의 불가 → 정상(판정 스킵).
 *       0 나눗셈을 원천 차단한다.</li>
 *   <li><b>최소표본</b> — count_total &lt; minSampleTotal 이면 정상. 저표본 버킷의 튄 실패율(예 1/2=50%)로
 *       오탐이 나는 것을 막는다.</li>
 *   <li><b>상대임계 하한</b> — failure_rate &lt; failureRateFloor 이면 정상. 절대적으로 낮은 실패율은
 *       z 가 커도 실질 위험이 아니다.</li>
 *   <li><b>z 임계</b> — z &lt; zThreshold 이면 정상. 평소 변동 범위 안이다.</li>
 * </ol>
 *
 * <p>프레임워크 의존이 전혀 없는 도메인 서비스 — {@link BaselineStrategy} 만 주입받는다.
 */
public class AnomalyEvaluator {

    private final BaselineStrategy baselineStrategy;

    public AnomalyEvaluator(BaselineStrategy baselineStrategy) {
        this.baselineStrategy = baselineStrategy;
    }

    public AnomalyDecision evaluate(double failureRate, long countTotal, double[] baselineWindow, AnomalyThreshold t) {
        Baseline baseline = baselineStrategy.compute(baselineWindow);

        if (baseline.isDegenerate()) {
            return normal(0.0, baseline, failureRate, countTotal,
                    "베이스라인 변동 없음(stddev=0 또는 표본부족) — 판정 스킵");
        }

        double z = (failureRate - baseline.mean()) / baseline.stddev();

        if (countTotal < t.minSampleTotal()) {
            return normal(z, baseline, failureRate, countTotal,
                    "최소표본 미달(count_total=%d < %d)".formatted(countTotal, t.minSampleTotal()));
        }
        if (failureRate < t.failureRateFloor()) {
            return normal(z, baseline, failureRate, countTotal,
                    "상대임계 하한 미만(failure_rate=%.4f < %.4f)".formatted(failureRate, t.failureRateFloor()));
        }
        if (z < t.zThreshold()) {
            return normal(z, baseline, failureRate, countTotal,
                    "정상 범위(z=%.2f < %.2f)".formatted(z, t.zThreshold()));
        }

        boolean critical = z >= t.criticalZ();
        String reason = "실패율 급증: failure_rate=%.4f, baseline=%.4f±%.4f, z=%.2f (임계 %.2f)"
                .formatted(failureRate, baseline.mean(), baseline.stddev(), z, t.zThreshold());
        return new AnomalyDecision(AnomalyVerdict.ANOMALY, z, baseline.mean(), baseline.stddev(),
                failureRate, countTotal, critical, reason);
    }

    private AnomalyDecision normal(double z, Baseline baseline, double failureRate, long countTotal, String reason) {
        return new AnomalyDecision(AnomalyVerdict.NORMAL, z, baseline.mean(), baseline.stddev(),
                failureRate, countTotal, false, reason);
    }
}
