package github.lms.lemuel.common.outbox.domain;

public enum OutboxEventStatus {
    /** 기록됐으나 아직 발행되지 않음 — 폴러 대상 */
    PENDING,
    /** 외부 이벤트 시스템에 성공적으로 발행됨 */
    PUBLISHED,
    /** 재시도 한계 초과 — 운영자 개입 필요 */
    FAILED
}
