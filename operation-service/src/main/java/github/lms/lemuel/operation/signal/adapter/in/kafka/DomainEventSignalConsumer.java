package github.lms.lemuel.operation.signal.adapter.in.kafka;

import github.lms.lemuel.operation.signal.application.port.in.RecordSignalUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 도메인 성공 이벤트 → 신호 버킷 <b>분모</b>(count_total) 적재 (Phase 2 채널 A 분모).
 *
 * <p>이미 흐르는 성공 이벤트(order.created / payment.captured)를 operation 전용
 * 컨슈머 그룹(lemuel-operation)으로 구독만 한다 — 신규 발행 비용 0, 다른 소비자에 영향 0.
 * 실패 분자(count_signal)는 Phase 2b 에서 실패 이벤트 신설로 채운다.
 *
 * <p>★ 멱등(processed_events) 미적용 — 통계 5분 버킷은 at-least-once 재전송에 강건하고
 * (드문 중복이 카운트를 거의 못 흔듦), 고volume 성공 이벤트마다 멱등 행을 쌓으면 테이블이
 * 무한 팽창한다. Phase 3 판정도 상대임계+z-score 라 소량 노이즈에 둔감하다.
 * (설계 문서 §3.1 대비 의도적 편차 — 구현 노트 참조.)
 *
 * <p>버킷 시각은 Kafka record timestamp(발행 시각 근사)를 쓴다 — 페이로드 파싱 없이
 * 카운트만 하므로 스키마 변화에 견고하다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class DomainEventSignalConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventSignalConsumer.class);
    private static final String GROUP = "lemuel-operation";

    static final String METRIC_ORDER = "order";
    static final String METRIC_PAYMENT = "payment";

    private final RecordSignalUseCase recordSignalUseCase;

    public DomainEventSignalConsumer(RecordSignalUseCase recordSignalUseCase) {
        this.recordSignalUseCase = recordSignalUseCase;
    }

    @KafkaListener(topics = "${app.ops.signal.topics.order-created:lemuel.order.created}", groupId = GROUP)
    public void onOrderCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        countAttempt(METRIC_ORDER, record, ack);
    }

    @KafkaListener(topics = "${app.ops.signal.topics.payment-captured:lemuel.payment.captured}", groupId = GROUP)
    public void onPaymentCaptured(ConsumerRecord<String, String> record, Acknowledgment ack) {
        countAttempt(METRIC_PAYMENT, record, ack);
    }

    private void countAttempt(String metricKey, ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            // signal=false — 성공 이벤트는 분모(시도)만 올린다.
            recordSignalUseCase.recordEvent(metricKey, false, Instant.ofEpochMilli(record.timestamp()));
        } catch (Exception e) {
            // 버킷 적재 실패는 통계 손실일 뿐이므로 ack 로 진행(무한 재시도로 컨슈머를 막지 않음).
            log.warn("신호 버킷 적재 실패 — 스킵: metricKey={} topic={} offset={}",
                    metricKey, record.topic(), record.offset(), e);
        } finally {
            ack.acknowledge();
        }
    }
}
