package github.lms.lemuel.common.outbox.application.port.out;

import github.lms.lemuel.common.outbox.domain.OutboxEvent;

import java.time.Duration;
import java.util.List;

/**
 * 멀티워커 발행을 위한 outbox 행 claim 포트.
 *
 * <p>여러 인스턴스가 동시에 폴링해도 {@code SELECT ... FOR UPDATE SKIP LOCKED} 로
 * 서로 겹치지 않는 PENDING 행만 가져간다. claim 한 행에는 리스(claimed_at)를 찍어,
 * 발행 완료 전 워커가 죽어도 리스 만료 후 다른 워커가 회수하도록 한다.
 */
public interface ClaimOutboxEventPort {

    /**
     * PENDING 이면서 미클레임이거나 리스가 만료된 행을 최대 limit 개 원자적으로 claim 한다.
     *
     * @param limit  한 번에 가져올 최대 행 수
     * @param lease  claim 리스 — 이 시간 안에 발행이 끝나지 않으면 다른 워커가 회수 가능
     * @param worker claim 주체 식별자 (운영 추적용)
     * @return 생성 시각 오름차순으로 정렬된 claim 된 이벤트들 (없으면 빈 리스트)
     */
    List<OutboxEvent> claimPending(int limit, Duration lease, String worker);

    /**
     * 발행에 실패해 재시도가 필요한(여전히 PENDING) 행들의 리스를 해제해 즉시 재클레임 가능하게 한다.
     */
    void releaseClaim(List<Long> ids);
}
