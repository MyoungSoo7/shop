package github.lms.lemuel.common.outbox.application.port.out;

import github.lms.lemuel.common.outbox.domain.OutboxEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbox 폴러 / Admin DLQ 콘솔이 outbox 레코드를 조회하기 위한 포트.
 */
public interface LoadOutboxEventPort {
    /**
     * 운영 지표용 — 현재 PENDING 개수.
     */
    long countPending();

    /**
     * DLQ Admin 콘솔 — 재시도 한계 초과한 FAILED 이벤트 페이지 조회.
     */
    List<OutboxEvent> findFailed(int offset, int limit);

    /**
     * DLQ 알람용 — 현재 FAILED 개수.
     */
    long countFailed();

    /**
     * Admin 작업용 단건 조회 (eventId UUID 기준).
     */
    Optional<OutboxEvent> findByEventId(UUID eventId);
}
