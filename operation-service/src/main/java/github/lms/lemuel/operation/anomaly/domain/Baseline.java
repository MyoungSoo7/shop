package github.lms.lemuel.operation.anomaly.domain;

/**
 * 롤링 베이스라인 산정 결과 (순수 VO) — 정상 기준선의 평균·표준편차.
 *
 * @param mean       베이스라인 평균
 * @param stddev     베이스라인 표준편차 (모표준편차 — 표본 전체 기준). 0 이면 변동 없음(z 계산 불가).
 * @param sampleSize 산정에 사용한 표본 수
 */
public record Baseline(double mean, double stddev, int sampleSize) {

    /** 표준편차 0(또는 표본 부족)이면 z-score 를 낼 수 없다 — 판정 스킵의 근거. */
    public boolean isDegenerate() {
        return sampleSize == 0 || stddev == 0.0;
    }
}
