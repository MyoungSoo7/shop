package github.lms.lemuel.operation.signal.application.port.in;

import java.time.Instant;
import java.util.List;

/**
 * 신호 시계열 조회 <b>인바운드 포트</b> — signal 이 다른 기능에 공개하는 읽기 창구.
 *
 * <p>이 포트가 생긴 이유: anomaly 의 어댑터가 signal 의 JPA 엔티티와 스프링 데이터 리포지토리를
 * 직접 들고 있었다. 어댑터가 남의 어댑터를 읽는 형태라, signal 이 저장 방식을 바꾸면
 * 아무 관계도 없어 보이는 anomaly 가 깨졌다.
 *
 * <p>시그니처에 signal 의 도메인·엔티티 타입이 없다 — {@link Bucket} 은 이 포트가 소유한다.
 */
public interface QueryMetricSeriesUseCase {

    /**
     * 지정 metric_key 의 <b>마감된</b> 버킷을 시간 오름차순으로 최대 limit 개 반환한다.
     *
     * <p>"마감" 의 기준은 signal 이 정한다 — {@code asOf} 가 속한 진행 중인 버킷은 부분 집계이므로
     * 제외한다. <b>호출자는 버킷 폭을 알 필요가 없다.</b> (이전에는 호출자가 직접
     * {@code BucketWindow.floor(now, bucketSeconds)} 를 계산해서 넘겼다. 버킷 정렬은
     * signal 의 개념인데 그 지식이 밖으로 새 있었다.)
     *
     * @param metricKey 대상 metric_key (예 "settlement")
     * @param asOf      기준 시각 — 이 시각이 속한 버킷과 그 이후는 제외된다
     * @param limit     최대 조회 개수
     * @return 오름차순 버킷 목록 (없으면 빈 리스트)
     */
    List<Bucket> closedBuckets(String metricKey, Instant asOf, int limit);

    /**
     * 버킷 한 칸의 조회 결과.
     *
     * @param bucketStart 버킷 시작 시각
     * @param countTotal  카운터형 시도 수(분모)
     * @param countSignal 카운터형 신호 수(분자)
     * @param failureRate 실패율 — 분모가 0 이면 0.0
     */
    record Bucket(Instant bucketStart, long countTotal, long countSignal, double failureRate) {
    }
}
