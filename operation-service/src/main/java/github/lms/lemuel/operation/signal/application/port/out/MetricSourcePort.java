package github.lms.lemuel.operation.signal.application.port.out;

import java.util.OptionalDouble;

/** 외부 메트릭 소스(Prometheus) 인스턴트 쿼리 아웃바운드 포트. */
public interface MetricSourcePort {

    /**
     * PromQL 인스턴트 쿼리를 실행해 스칼라 값을 얻는다.
     *
     * @return 결과 벡터 첫 샘플의 값. 결과 없음(예: redis_up 이 down 이라 시계열 부재)·오류 시 empty.
     */
    OptionalDouble queryInstant(String promQl);
}
