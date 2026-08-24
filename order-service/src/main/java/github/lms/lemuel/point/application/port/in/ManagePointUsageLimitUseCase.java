package github.lms.lemuel.point.application.port.in;

import github.lms.lemuel.point.domain.PointUsageLimit;
import github.lms.lemuel.point.domain.PointUsageLimitType;

import java.math.BigDecimal;

/**
 * 주문당 포인트 사용 상한 — 조회 · 검사 · 변경.
 *
 * <p>검사({@link #assertWithinLimit})는 결제가 부른다. 상한은 결제 금액에 대한 규칙이라 포인트
 * 원장 단독으로는 판단할 수 없고(주문 금액을 모른다), 그렇다고 결제가 정책을 해석하면 규칙이
 * 두 곳에 생긴다 — 그래서 "금액을 받아 포인트가 판단"하는 모양으로 둔다.
 */
public interface ManagePointUsageLimitUseCase {

    PointUsageLimit current();

    /** 이 주문에서 요청한 포인트 사용액이 상한 안인지 검사한다. 넘으면 예외. */
    void assertWithinLimit(BigDecimal orderAmount, BigDecimal requestedPointAmount);

    /** 상한 변경(운영 콘솔 전용). 유형이 요구하지 않는 값은 무시된다. */
    PointUsageLimit update(PointUsageLimitType type, BigDecimal limitAmount,
                           BigDecimal limitRatioPercent, String actor);
}
