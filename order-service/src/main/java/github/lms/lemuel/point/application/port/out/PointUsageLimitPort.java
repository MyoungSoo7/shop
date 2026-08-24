package github.lms.lemuel.point.application.port.out;

import github.lms.lemuel.point.domain.PointUsageLimit;

/**
 * 포인트 사용 상한 정책 조회·저장.
 *
 * <p>정책 행이 없으면 {@link PointUsageLimit#none()} 으로 착지한다 — 정책이 없다는 이유로 고객의
 * 포인트 사용을 막지 않는다(이 기능 도입 전의 동작이 그대로 유지된다).
 */
public interface PointUsageLimitPort {

    PointUsageLimit load();

    PointUsageLimit save(PointUsageLimit limit, String actor);
}
