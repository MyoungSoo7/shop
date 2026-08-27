package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.in.ConfirmRewardUseCase;
import github.lms.lemuel.marketing.application.port.out.RewardGrantPort;
import github.lms.lemuel.marketing.domain.RewardGrant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 포인트 적립 결과를 보상에 반영한다.
 *
 * <p>여기가 왕복의 반환점이다. 마케팅이 {@code lemuel.marketing.reward_requested} 를 내면
 * order-service 가 원장에 적립하고 {@code lemuel.point.granted} 를 낸다. 그 이벤트에는
 * 적립의 {@code referenceId} 가 실려 있고, 그게 우리가 만든 보상의 id 다.
 *
 * <p>모르는 id 는 조용히 지나간다. {@code lemuel.point.granted} 에는 주문 적립·수동 지급 등
 * 마케팅과 무관한 적립도 전부 실려 오기 때문이다. 그걸 예외로 만들면 컨슈머가 남의 이벤트마다
 * 재시도하다 DLQ 로 간다.
 */
@Service
public class RewardConfirmationService implements ConfirmRewardUseCase {

    private static final Logger log = LoggerFactory.getLogger(RewardConfirmationService.class);

    private final RewardGrantPort rewardGrantPort;

    public RewardConfirmationService(RewardGrantPort rewardGrantPort) {
        this.rewardGrantPort = rewardGrantPort;
    }

    @Override
    @Transactional
    public void confirm(UUID rewardId) {
        if (rewardId == null) {
            return;
        }
        Optional<RewardGrant> found = rewardGrantPort.findById(rewardId);
        if (found.isEmpty()) {
            log.debug("마케팅 보상이 아닌 적립 통지 — 무시한다: referenceId={}", rewardId);
            return;
        }
        RewardGrant grant = found.get();
        grant.markConfirmed();   // 이미 CONFIRMED 면 아무 일도 하지 않는다 (at-least-once 재수신)
        rewardGrantPort.save(grant);
    }
}
