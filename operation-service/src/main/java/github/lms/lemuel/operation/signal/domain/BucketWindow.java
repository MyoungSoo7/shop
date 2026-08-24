package github.lms.lemuel.operation.signal.domain;

import java.time.Instant;

/**
 * 신호 시각을 고정 폭(기본 5분) 버킷 시작 시각으로 정렬하는 순수 함수.
 *
 * <p>UTC epoch 초를 bucketSeconds 로 내림(floor) 정렬한다 — 같은 5분 창의 이벤트/게이지는
 * 모두 동일 bucket_start 로 모여 UPSERT 로 누적된다. Phase 3 베이스라인(요일·시간대 평균)의
 * 최소 단위이므로, 버킷 폭은 탐지 단위와 일치해야 한다.
 */
public final class BucketWindow {

    private BucketWindow() {
    }

    /**
     * @param instant       신호 발생 시각
     * @param bucketSeconds 버킷 폭(초) — 300(5분) 등. 양수여야 한다.
     * @return instant 가 속한 버킷의 시작 시각 (nanos 제거, bucketSeconds 정렬)
     */
    public static Instant floor(Instant instant, int bucketSeconds) {
        if (bucketSeconds <= 0) {
            throw new IllegalArgumentException("bucketSeconds must be positive: " + bucketSeconds);
        }
        long epochSecond = instant.getEpochSecond();
        long floored = Math.floorDiv(epochSecond, bucketSeconds) * (long) bucketSeconds;
        return Instant.ofEpochSecond(floored);
    }
}
