package github.lms.lemuel.operation.anomaly.domain;

import java.time.Instant;

/**
 * 판정 대상 시계열의 한 칸 — 이상 탐지가 실제로 필요로 하는 것만 담는다.
 *
 * <p>탐지는 <b>비율과 표본 수</b> 두 개로만 돌아간다. 비율은 베이스라인 대비 z 값 계산에,
 * 표본 수는 최소 표본 게이트에 쓰인다. 그 외 원본 컬럼(게이지 합·최대·표본 수 …)은
 * 탐지의 관심사가 아니다.
 *
 * <p>이전에는 signal 의 {@code MetricBucket} 을 그대로 썼다. 편했지만, 탐지에 쓰지도 않는
 * 게이지 필드까지 딸려 오면서 <b>signal 이 적재 모델을 바꾸면 anomaly 가 깨지는</b> 관계가 생겼다.
 *
 * @param bucketStart 관측 구간 시작 시각
 * @param ratio       판정 대상 비율 (실패율)
 * @param sampleTotal 그 비율의 분모 — 표본이 적으면 판정을 신뢰하지 않는다
 */
public record MetricPoint(Instant bucketStart, double ratio, long sampleTotal) {
}
