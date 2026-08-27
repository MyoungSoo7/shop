package github.lms.lemuel.marketing.application.port.in;

import java.util.UUID;

/**
 * 원장 적립 결과를 보상에 반영한다 — {@code lemuel.point.granted} 컨슈머가 부른다.
 *
 * <p>{@code rewardId} 는 order-service 가 적립에 실어 둔 {@code referenceId} 이고, 그게 곧
 * 우리 {@code reward_grants.id} 다. 우리가 낸 요청이 아니면(다른 출처의 적립) 조용히 무시한다.
 */
public interface ConfirmRewardUseCase {
    void confirm(UUID rewardId);
}
