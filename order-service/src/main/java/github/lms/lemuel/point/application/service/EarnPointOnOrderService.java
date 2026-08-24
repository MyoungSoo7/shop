package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.EarnPointOnOrderUseCase;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase.GrantPointCommand;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase.GrantPointResult;
import github.lms.lemuel.point.application.port.out.PointEarnPolicyPort;
import github.lms.lemuel.point.domain.PointEarnPolicy;
import github.lms.lemuel.point.domain.PointEarnPolicyResolver;
import github.lms.lemuel.point.domain.PointLotOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 주문 확정 적립 — 정책을 해석해 적립액을 정하고 로트를 발급한다.
 *
 * <p>이 서비스가 정하는 것은 "얼마를"뿐이다. "언제"는 주문 도메인이 정하고(배송 완료 전이),
 * "어떻게 장부에 남기는가"는 {@link GrantPointService} 가 정한다. 셋을 한 곳에 섞으면
 * 적립 시점을 바꿀 때마다 원장 코드를 건드리게 된다.
 *
 * <p>적립률 정책이 없으면 조용히 0 을 돌려준다 — <b>기본 적립률로 폴백하지 않는다.</b>
 * 표가 비었을 때 도입 전과 동일하게 동작해야 하기 때문이다(무행동 착지).
 */
@Service
@Transactional
public class EarnPointOnOrderService implements EarnPointOnOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(EarnPointOnOrderService.class);

    /** 원장 자연키의 일부 — 값이 바뀌면 멱등이 깨진다. */
    private static final String REFERENCE_TYPE = "ORDER";

    private final PointEarnPolicyPort policyPort;
    private final GrantPointUseCase grantPointUseCase;

    public EarnPointOnOrderService(PointEarnPolicyPort policyPort, GrantPointUseCase grantPointUseCase) {
        this.policyPort = policyPort;
        this.grantPointUseCase = grantPointUseCase;
    }

    @Override
    public EarnPointResult earn(EarnPointCommand command) {
        // Phase 1 은 GLOBAL 정책만 쓴다. 등급·카테고리 키는 회원 등급이 붙을 때 채운다.
        Optional<PointEarnPolicy> policy = PointEarnPolicyResolver.resolve(
                policyPort.loadCandidates(command.on(), null, null), command.on());
        if (policy.isEmpty()) {
            log.debug("적립률 정책 없음 — 적립하지 않는다: orderId={}", command.orderId());
            return new EarnPointResult(BigDecimal.ZERO, null);
        }

        BigDecimal earned = policy.get().earnFor(command.eligibleAmount());
        if (earned.signum() <= 0) {
            // 1원이 안 되는 적립은 로트를 만들지 않는다 — 0원 로트는 원장만 어지럽힌다.
            log.debug("적립액 1원 미만 — 적립하지 않는다: orderId={}, 대상금액={}",
                    command.orderId(), command.eligibleAmount());
            return new EarnPointResult(BigDecimal.ZERO, null);
        }

        GrantPointResult granted = grantPointUseCase.grant(new GrantPointCommand(
                command.userId(), earned, PointLotOrigin.ORDER_EARN,
                REFERENCE_TYPE, String.valueOf(command.orderId()),
                policy.get().expiryFrom(OffsetDateTime.now()),
                command.actor(), null));

        log.info("주문 적립: orderId={}, userId={}, 대상금액={}, 적립={}, 적립률={}",
                command.orderId(), command.userId(), command.eligibleAmount(),
                earned, policy.get().getEarnRate());
        return new EarnPointResult(earned, granted.lotId());
    }
}
