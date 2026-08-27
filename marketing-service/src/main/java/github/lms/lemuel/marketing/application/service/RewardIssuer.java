package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.out.PublishRewardRequestedPort;
import github.lms.lemuel.marketing.application.port.out.RewardGrantPort;
import github.lms.lemuel.marketing.domain.RewardGrant;
import github.lms.lemuel.marketing.domain.RewardSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 보상 요청을 만드는 한 자리.
 *
 * <p>출석 일일보상·목표보상·럭키박스 당첨 세 곳이 같은 일을 한다. 레거시는 세 곳이 각자
 * {@code mileageService} 를 불렀고, 그래서 만료일을 넣는 곳과 안 넣는 곳, 메모 형식이 다른 곳이
 * 생겼다. 지급 규칙이 한 곳에 있으면 그 차이가 생길 자리가 없다.
 *
 * <p>여기서 하는 일은 두 가지뿐이다 — 중복 요청을 걸러 내고, 즉시 지급이면 outbox 에 싣는다.
 * 포인트를 실제로 더하는 것은 order-service 이고 이 서비스는 그 결과를 나중에 통지로 받는다.
 */
@Component
public class RewardIssuer {

    private final RewardGrantPort rewardGrantPort;
    private final PublishRewardRequestedPort publishRewardRequestedPort;

    public RewardIssuer(RewardGrantPort rewardGrantPort, PublishRewardRequestedPort publishRewardRequestedPort) {
        this.rewardGrantPort = rewardGrantPort;
        this.publishRewardRequestedPort = publishRewardRequestedPort;
    }

    /**
     * 보상을 발행한다. 이미 같은 원본으로 발행된 게 있으면 그걸 그대로 돌려준다.
     *
     * @param scheduledOn 지급 예정일. null 이면 즉시 요청(같은 트랜잭션에서 outbox 적재).
     * @return 발행됐거나 이미 있던 보상. 금액이 0 이하면 null — 줄 게 없으면 만들지 않는다.
     */
    public RewardGrant issue(RewardSource source, UUID referenceId, UUID campaignId, String campaignName,
                             String memberRef, BigDecimal amount, LocalDate expiresOn, String memo,
                             LocalDate scheduledOn) {
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        // 원본 한 건에 보상 한 건. UNIQUE (source, reference_id) 가 최종 방어선이고 이 조회는
        // 재시도가 예외까지 가지 않게 하는 앞단이다.
        RewardGrant existing = rewardGrantPort.findByReference(source, referenceId).orElse(null);
        if (existing != null) {
            return existing;
        }

        UUID rewardId = UUID.randomUUID();
        RewardGrant grant = (scheduledOn == null)
                ? RewardGrant.requestNow(rewardId, source, referenceId, campaignId, memberRef, amount, expiresOn, memo)
                : RewardGrant.scheduled(rewardId, source, referenceId, campaignId, memberRef, amount, expiresOn, memo,
                        scheduledOn);

        RewardGrant saved = rewardGrantPort.save(grant);
        if (scheduledOn == null) {
            publishRewardRequestedPort.rewardRequested(saved, campaignName);
        }
        return saved;
    }
}
