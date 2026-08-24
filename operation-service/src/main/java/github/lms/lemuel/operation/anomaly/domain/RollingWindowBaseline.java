package github.lms.lemuel.operation.anomaly.domain;

/**
 * 단순 롤링윈도우 베이스라인 — 직전 N개 버킷의 산술평균과 모표준편차(population stddev).
 *
 * <p>계절성을 무시하는 대신 히스토리가 거의 없어도(수 일치) 즉시 동작한다 — Phase 3 첫 컷의
 * 실용적 선택. 모표준편차를 쓰는 이유: 윈도우가 "정상 구간 전체"를 표본이 아니라 모집단으로
 * 간주하는 관측이고, 표본표준편차(n-1)의 과대추정으로 임계가 흔들리는 것을 피하기 위함.
 */
public class RollingWindowBaseline implements BaselineStrategy {

    @Override
    public Baseline compute(double[] window) {
        if (window == null || window.length == 0) {
            return new Baseline(0.0, 0.0, 0);
        }
        int n = window.length;
        double sum = 0.0;
        for (double v : window) {
            sum += v;
        }
        double mean = sum / n;

        double sqDiff = 0.0;
        for (double v : window) {
            double d = v - mean;
            sqDiff += d * d;
        }
        double stddev = Math.sqrt(sqDiff / n);

        return new Baseline(mean, stddev, n);
    }
}
