package github.lms.lemuel.common.outbox.application.port.in;

import github.lms.lemuel.common.outbox.domain.OutboxEvent;

import java.util.List;
import java.util.UUID;

/**
 * 운영자 (DLQ 콘솔 / Admin REST) 가 outbox 이벤트를 조회·재처리·스킵하기 위한 인바운드 포트.
 *
 * <p>이 포트의 모든 메서드는 운영자 액션을 audit 로그에 남긴다 (구현체 책임).
 */
public interface OutboxAdminUseCase {

    /**
     * 재시도 한계 초과 (FAILED) 이벤트 페이지 조회.
     */
    List<OutboxEvent> listFailed(int offset, int limit);

    /**
     * FAILED 이벤트 1건을 PENDING 으로 되돌려 다음 폴링 주기에 재발행되도록 한다.
     * 외부 시스템 (PG, 컨슈머 그룹) 의 일시 장애가 복구된 후에 사용.
     *
     * @return 재처리 큐로 돌아간 이벤트
     * @throws IllegalStateException FAILED 가 아닌 이벤트일 때
     */
    OutboxEvent retry(UUID eventId);

    /**
     * FAILED 이벤트 1건을 PUBLISHED 로 강제 마킹한다 (운영자 수동 보정 완료 등).
     * lastError 필드에 [SKIPPED] 사유가 기록되어 사후 감사가 가능하다.
     *
     * @param reason 스킵 사유 (필수, 감사 추적용)
     */
    OutboxEvent skip(UUID eventId, String reason, String operatorId);

    /**
     * DLQ 모니터링 — 현재 FAILED 개수.
     */
    long failedCount();
}
