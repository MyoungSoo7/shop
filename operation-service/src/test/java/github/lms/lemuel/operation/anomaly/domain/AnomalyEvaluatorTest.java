package github.lms.lemuel.operation.anomaly.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyEvaluatorTest {

    private final AnomalyEvaluator evaluator = new AnomalyEvaluator(new RollingWindowBaseline());

    // z-threshold 3.0, critical 5.0, windowSize 2, minSample 30, floor 0.10, K 3
    private static final AnomalyThreshold T = new AnomalyThreshold(3.0, 5.0, 2, 30, 0.10, 3);

    // baseline {0.0, 0.25} → mean 0.125, stddev 0.125 (2의 거듭제곱 → z 가 정확히 떨어짐)
    // z = (fr - 0.125) / 0.125
    private static final double[] BASELINE = {0.0, 0.25};

    @Test
    @DisplayName("z가 criticalZ 이상인 급증 → ANOMALY + critical")
    void spike_isCriticalAnomaly() {
        AnomalyDecision d = evaluator.evaluate(0.75, 100, BASELINE, T); // z = 5.0

        assertThat(d.isAnomaly()).isTrue();
        assertThat(d.zScore()).isEqualTo(5.0);
        assertThat(d.critical()).isTrue();
    }

    @Test
    @DisplayName("z가 임계 이상이지만 criticalZ 미만 → ANOMALY + WARNING(critical=false)")
    void moderateAnomaly_isWarning() {
        AnomalyDecision d = evaluator.evaluate(0.625, 100, BASELINE, T); // z = 4.0

        assertThat(d.isAnomaly()).isTrue();
        assertThat(d.zScore()).isEqualTo(4.0);
        assertThat(d.critical()).isFalse();
    }

    @Test
    @DisplayName("z가 임계 경계값(정확히 3.0) → ANOMALY (>= 포함)")
    void boundaryZ_isAnomaly() {
        AnomalyDecision d = evaluator.evaluate(0.5, 100, BASELINE, T); // z = 3.0

        assertThat(d.zScore()).isEqualTo(3.0);
        assertThat(d.isAnomaly()).isTrue();
    }

    @Test
    @DisplayName("z가 임계 미만 → NORMAL")
    void belowZThreshold_isNormal() {
        AnomalyDecision d = evaluator.evaluate(0.25, 100, BASELINE, T); // z = 1.0

        assertThat(d.zScore()).isEqualTo(1.0);
        assertThat(d.isAnomaly()).isFalse();
    }

    @Test
    @DisplayName("최소표본 미달 → NORMAL (저표본 오탐 차단)")
    void belowMinSample_isNormal() {
        AnomalyDecision d = evaluator.evaluate(0.75, 10, BASELINE, T); // z=5 but total 10 < 30

        assertThat(d.isAnomaly()).isFalse();
        assertThat(d.reason()).contains("최소표본");
    }

    @Test
    @DisplayName("상대임계 하한 미만 → NORMAL (절대적으로 낮은 실패율은 이상 아님)")
    void belowFloor_isNormal() {
        // baseline {0.0, 0.03125} → mean/stddev 0.015625; fr 0.0625 → z=3.0 이지만 fr < floor 0.10
        AnomalyDecision d = evaluator.evaluate(0.0625, 100, new double[]{0.0, 0.03125}, T);

        assertThat(d.zScore()).isEqualTo(3.0);
        assertThat(d.isAnomaly()).isFalse();
        assertThat(d.reason()).contains("상대임계");
    }

    @Test
    @DisplayName("베이스라인 변동 없음(stddev=0) → NORMAL (0 나눗셈 차단)")
    void degenerateBaseline_isNormal() {
        AnomalyDecision d = evaluator.evaluate(0.99, 1000, new double[]{0.05, 0.05}, T);

        assertThat(d.isAnomaly()).isFalse();
        assertThat(d.zScore()).isEqualTo(0.0);
        assertThat(d.reason()).contains("베이스라인 변동 없음");
    }
}
