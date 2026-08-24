package github.lms.lemuel.point.domain.exception;

import java.math.BigDecimal;

/**
 * 주문당 포인트 사용 상한 초과.
 *
 * <p>잔액 부족({@link InsufficientPointException})과 구분한다 — 잔액은 있는데 정책이 막은 것이라,
 * 고객에게는 "포인트가 모자랍니다"가 아니라 "이 주문에는 N 포인트까지 쓸 수 있습니다"로 안내해야 한다.
 *
 * <p>{@link InvalidPointStateException} 을 상속해 사용자 입력으로 도달 가능한 정상 거절로 매핑된다
 * (불변식 위반이 아니다 — 500 으로 떨어지면 안 된다).
 */
public class PointUsageLimitExceededException extends InvalidPointStateException {

    public PointUsageLimitExceededException(BigDecimal requested, BigDecimal maxUsable) {
        super("이 주문에 사용할 수 있는 포인트를 초과했습니다: 요청=" + requested + ", 최대=" + maxUsable,
                "ACTIVE", "point-usage-limit");
    }
}
