package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.ManagePointUsageLimitUseCase;
import github.lms.lemuel.point.application.port.out.PointUsageLimitPort;
import github.lms.lemuel.point.domain.PointUsageLimit;
import github.lms.lemuel.point.domain.PointUsageLimitType;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@Transactional(readOnly = true)
public class ManagePointUsageLimitService implements ManagePointUsageLimitUseCase {

    private static final Logger log = LoggerFactory.getLogger(ManagePointUsageLimitService.class);

    private final PointUsageLimitPort port;

    public ManagePointUsageLimitService(PointUsageLimitPort port) {
        this.port = port;
    }

    @Override
    public PointUsageLimit current() {
        return port.load();
    }

    @Override
    public void assertWithinLimit(BigDecimal orderAmount, BigDecimal requestedPointAmount) {
        if (requestedPointAmount == null || requestedPointAmount.signum() <= 0) {
            return;   // 포인트를 쓰지 않는 결제는 정책 조회조차 필요 없다
        }
        port.load().assertWithin(orderAmount, requestedPointAmount);
    }

    @Override
    @Transactional
    public PointUsageLimit update(PointUsageLimitType type, BigDecimal limitAmount,
                                  BigDecimal limitRatioPercent, String actor) {
        if (type == null) {
            throw new InvalidPointStateException("사용 상한 유형은 필수입니다", "NONE", "usage-limit");
        }
        // 값 검증은 도메인 팩토리가 한다 — 음수 정액·범위 밖 비율은 여기 도달 전에 거절된다.
        PointUsageLimit limit = switch (type) {
            case NONE -> PointUsageLimit.none();
            case FIXED_AMOUNT -> PointUsageLimit.fixedAmount(limitAmount);
            case ORDER_RATIO -> PointUsageLimit.orderRatio(limitRatioPercent);
        };
        PointUsageLimit saved = port.save(limit, actor);
        log.info("포인트 사용 상한 변경: type={}, amount={}, ratio={}, actor={}",
                type, limitAmount, limitRatioPercent, actor);
        return saved;
    }
}
