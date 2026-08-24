package github.lms.lemuel.operation.signal.application.port.in;

/** 설정된 PromQL 쿼리들을 1회 폴링해 게이지 버킷에 적재하는 유스케이스. */
public interface PollMetricsUseCase {

    /**
     * 모든 설정 쿼리를 실행해 각각의 게이지 버킷에 누적한다.
     *
     * @return 성공적으로 적재된 쿼리 수 (일부 실패해도 나머지는 진행)
     */
    int pollOnce();
}
