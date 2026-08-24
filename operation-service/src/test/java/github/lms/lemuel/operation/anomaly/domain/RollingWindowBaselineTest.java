package github.lms.lemuel.operation.anomaly.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RollingWindowBaselineTest {

    private final RollingWindowBaseline strategy = new RollingWindowBaseline();

    @Test
    @DisplayName("평균·모표준편차를 정확히 산정한다")
    void computesMeanAndPopulationStddev() {
        // {2,4,4,4,5,5,7,9} → mean 5, population stddev 2
        Baseline b = strategy.compute(new double[]{2, 4, 4, 4, 5, 5, 7, 9});

        assertThat(b.mean()).isEqualTo(5.0);
        assertThat(b.stddev()).isEqualTo(2.0);
        assertThat(b.sampleSize()).isEqualTo(8);
        assertThat(b.isDegenerate()).isFalse();
    }

    @Test
    @DisplayName("빈 윈도우는 퇴화(degenerate) — 판정 불가")
    void emptyWindowIsDegenerate() {
        Baseline b = strategy.compute(new double[]{});

        assertThat(b.sampleSize()).isZero();
        assertThat(b.isDegenerate()).isTrue();
    }

    @Test
    @DisplayName("변동 없는(상수) 윈도우는 stddev=0 → 퇴화")
    void constantWindowIsDegenerate() {
        Baseline b = strategy.compute(new double[]{0.03, 0.03, 0.03, 0.03});

        assertThat(b.mean()).isEqualTo(0.03);
        assertThat(b.stddev()).isZero();
        assertThat(b.isDegenerate()).isTrue();
    }
}
