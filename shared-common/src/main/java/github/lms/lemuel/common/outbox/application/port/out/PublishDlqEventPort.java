package github.lms.lemuel.common.outbox.application.port.out;

import github.lms.lemuel.common.outbox.domain.OutboxEvent;

/**
 * Outbox 이벤트가 재시도 한계 초과로 FAILED 가 되었을 때, 별도의 Dead Letter
 * 토픽으로 발행하기 위한 아웃바운드 포트.
 *
 * <p>주 토픽과 분리된 DLQ 를 갖는 이유:
 * <ul>
 *   <li>실패 이벤트가 정상 컨슈머의 처리량을 차단하지 않음 (queue head-of-line blocking 제거)</li>
 *   <li>운영팀이 DLQ 만 별도로 모니터링·알람 설정 가능</li>
 *   <li>DLQ 컨슈머가 알람·티켓 자동 발행 등 별도 워크플로 가능</li>
 * </ul>
 *
 * <p>구현체:
 * <ul>
 *   <li>{@code KafkaDlqPublisher} — 운영용. 토픽 명: {@code lemuel.dlq.<aggregate>.<event>}</li>
 *   <li>{@code NoOpDlqPublisher} — Kafka 비활성 시 폴백 (로그만 남김)</li>
 * </ul>
 */
public interface PublishDlqEventPort {
    /**
     * FAILED 이벤트를 DLQ 로 발행. 실패해도 원본 outbox 상태에는 영향 없음 (이미 FAILED).
     */
    void publishToDlq(OutboxEvent event);
}
