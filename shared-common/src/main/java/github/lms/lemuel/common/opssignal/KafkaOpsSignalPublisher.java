package github.lms.lemuel.common.opssignal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 운영 관제 실패 신호를 Kafka 로 발행하는 best-effort 어댑터 (app.kafka.enabled=true).
 *
 * <p>★ 설계 핵심 — <b>비즈니스 트랜잭션 무영향</b>:
 * <ul>
 *   <li>Outbox 미사용 — 실패는 흔히 트랜잭션 롤백을 동반하므로 outbox 행도 함께 사라진다.
 *       실패 신호는 out-of-band(직접 Kafka)로 보내야 롤백돼도 관측된다.</li>
 *   <li>비동기 send — {@code .get()} 없이 fire-and-forget. 프로듀서 버퍼에 넣고 즉시 반환.</li>
 *   <li>절대 throw 안 함 — 직렬화·전송 준비 단계의 어떤 예외도 삼키고 로그만. 호출한 실패 처리
 *       경로(결제/정산 catch 블록 등)를 2차 예외로 오염시키지 않는다.</li>
 * </ul>
 * 통계 신호라 at-least-once/드문 유실 모두 5분 버킷에 무해하다(Phase 2a 판단과 동일).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaOpsSignalPublisher implements OpsSignalPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaOpsSignalPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String serviceName;

    public KafkaOpsSignalPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper objectMapper,
                                   @Value("${spring.application.name:unknown}") String serviceName) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.serviceName = serviceName;
    }

    @Override
    public void emit(OpsSignalCategory category, String entityType, String entityId, Map<String, Object> attributes) {
        emit(new OpsSignal(category, serviceName, entityType, entityId,
                OpsSignal.SEVERITY_ERROR, Instant.now(),
                attributes == null ? Map.of() : attributes));
    }

    @Override
    public void emit(OpsSignal signal) {
        try {
            String payload = objectMapper.writeValueAsString(signal);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    signal.category().topic(), signal.entityId(), payload);
            record.headers().add(new RecordHeader("event_id",
                    UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
            // 비동기 — 실패는 콜백에서 로그만. 호출 스레드는 즉시 반환한다.
            kafkaTemplate.send(record).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("ops-signal 발행 실패 (무시): category={} entityId={}",
                            signal.category(), signal.entityId(), ex);
                }
            });
        } catch (Exception e) {
            // 직렬화/레코드 조립 단계 예외 — 절대 호출자에게 전파하지 않는다.
            log.warn("ops-signal 발행 준비 실패 (무시): category={} entityId={}",
                    signal.category(), signal.entityId(), e);
        }
    }
}
