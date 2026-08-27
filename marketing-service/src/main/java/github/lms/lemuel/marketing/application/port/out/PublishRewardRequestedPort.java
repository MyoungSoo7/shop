package github.lms.lemuel.marketing.application.port.out;

import github.lms.lemuel.marketing.domain.RewardGrant;

/**
 * 보상 지급 요청을 밖으로 내보낸다.
 *
 * <p>구현은 Kafka 로 곧장 쏘지 않고 <b>outbox 테이블에 같은 트랜잭션으로 적재</b>한다.
 * 출석 기록과 보상 요청이 한 트랜잭션 안에서 함께 커밋되거나 함께 사라져야 하기 때문이다.
 * 브로커에 직접 발행하면 커밋 직전에 실패했을 때 "포인트는 들어왔는데 출석 기록은 없는"
 * 상태가 남는다. 실제 전송은 폴러가 커밋 뒤에 한다.
 */
public interface PublishRewardRequestedPort {
    void rewardRequested(RewardGrant grant, String campaignName);
}
