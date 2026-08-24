package github.lms.lemuel.common.outbox.adapter.in.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.UUID;

/**
 * 소비 이벤트 격리·중복 추적 훅 (옵트인) — "조용한 유실" 경로 제거의 계약.
 *
 * <p>{@link IdempotentEventConsumer} 는 지금까지 event_id 누락/불량 레코드를 경고 로그 후
 * ack(유실)했고, 파싱 실패·계약 위반은 DLT 토픽으로만 남겼다. 이 훅을 주입하면 그 세 경로가
 * 모두 DB 격리 기록을 남긴다 — DLT 를 대체하지 않고 그 위에 추적 계층을 더한다.
 *
 * <p><b>구현 계약:</b>
 * <ul>
 *   <li>{@link #quarantine} 은 리스너 트랜잭션이 롤백(예외 rethrow → DLT)돼도 기록이 살아남도록
 *       <b>REQUIRES_NEW</b> 등 독립 트랜잭션으로 저장해야 한다.</li>
 *   <li>같은 (consumer_group, topic, partition, offset) 재전달은 중복 행을 만들지 않아야 한다(멱등).</li>
 *   <li>훅 내부 실패는 삼키지 말고 던져라 — 격리 기록조차 실패하면 ack 하지 않는 것이
 *       무유실에 안전하다.</li>
 * </ul>
 *
 * <p>주입하지 않으면(레거시 생성자) 기존 동작이 정확히 유지된다 — 소비 서비스 전체 하위 호환.
 */
public interface ConsumedEventQuarantine {

    /** 격리 원인 분류 — cause_detail 에 원인 상세(원본 헤더 값·예외 메시지)를 병기한다. */
    enum Cause {
        /** event_id 헤더 자체가 없음. */
        MISSING_EVENT_ID,
        /** event_id 헤더가 UUID 로 파싱 불가. */
        INVALID_EVENT_ID,
        /** payload JSON 파싱 실패 또는 필수 필드 계약 위반. */
        INVALID_PAYLOAD,
    }

    /**
     * 처리 불가 레코드를 원인·원본 payload 증거와 함께 격리 기록한다.
     *
     * @param consumerGroup 격리를 발견한 컨슈머 그룹
     * @param cause         격리 원인 분류
     * @param causeDetail   원인 상세(불량 헤더 원문·예외 메시지 등, nullable)
     * @param record        원본 레코드 — topic/partition/offset/payload 증거의 출처
     * @param eventId       추출에 성공한 경우의 event_id (MISSING/INVALID 면 null)
     */
    void quarantine(String consumerGroup, Cause cause, String causeDetail,
                    ConsumerRecord<String, String> record, UUID eventId);

    /**
     * 이미 처리된 event_id 의 재도착(중복)을 추적한다. 기본 no-op —
     * 3분류(PROCESSED/DUPLICATE/QUARANTINED) 조회가 필요한 서비스만 구현한다.
     */
    default void duplicate(String consumerGroup, UUID eventId, ConsumerRecord<String, String> record) {
        // opt-in observability — 기본은 기록하지 않는다
    }
}
