package github.lms.lemuel.operation.anomaly.domain;

/**
 * 정상 기준선(baseline) 산정 전략 — Phase 3 는 롤링윈도우 1종({@link RollingWindowBaseline})만
 * 제공하고, 계절성(요일×시간대) 전략은 이 인터페이스로 선점만 해 둔다(히스토리가 충분히
 * 쌓이면 구현체 교체로 확장).
 */
public interface BaselineStrategy {

    /**
     * 직전 버킷들의 관측값(예: failure_rate)으로 정상 기준선을 산정한다.
     *
     * @param window 시간순 관측값 배열 (판정 대상 버킷은 제외한 직전 N개)
     * @return 평균·표준편차·표본수
     */
    Baseline compute(double[] window);
}
